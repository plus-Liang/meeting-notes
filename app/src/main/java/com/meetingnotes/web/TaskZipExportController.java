package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 批量导出 zip（T4.27，用户清单 1.3；从 {@link TaskExportController} 独立成类，先例同
 * TaskCountsController / RestructureController——原类顶 M3 行数上限）：
 * {@code GET /api/v1/tasks/export?ids=1,2,3}（≤20、去重保序）。
 *
 * <p>流式 {@link StreamingResponseBody} + {@link ZipOutputStream}（**不整体进内存**）；每条
 * {@code <id>-<安全文件名>.md} 经 {@link TaskExportController#render} 渲染——与单条导出
 * {@code GET /tasks/{id}/export?format=md}（默认 include）**逐字节一致**，不重复实现；
 * 不存在/非 DONE/非本人 → 跳过并写 {@code _errors.txt}（{@code task_id: reason}）；
 * 全部跳过 → 400 {@code INVALID_PARAM}（流式开始前预检，接入方拿到明确错误而非空 zip）。
 * {@code Content-Disposition: attachment; filename="meeting-notes-<yyyyMMdd-HHmm>.zip"}。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskZipExportController {

    /** 认证接入前固定单用户（与 TaskController 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    /** zip 批量上限（卡口径 ≤20）。 */
    static final int MAX_ZIP_IDS = 20;

    /** zip 内文件名的安全长度上限（超长保留尾部，避免 entry 名溢出）。 */
    private static final int MAX_ENTRY_BASE_LEN = 80;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TaskZipExportController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/tasks/export")
    public ResponseEntity<StreamingResponseBody> exportZip(
            @RequestParam(name = "ids", required = false) String ids) {
        List<Long> idList = parseIds(ids);
        List<Long> exportable = filterExportable(idList, DEFAULT_USER_ID, jdbc);
        if (exportable.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM",
                    "没有可导出的任务（全部不存在、未完成或无转写文本）");
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now());
        /* 流式阶段遍历**完整请求列表**（不是预检后的子集）：预检剔除的 id 也要进 _errors.txt（卡口径"跳过逐条说明"） */
        StreamingResponseBody body = out ->
                streamZip(out, idList, DEFAULT_USER_ID, jdbc, mapper, TaskExportController.parseInclude(null));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/zip"))
                .header("Content-Disposition", "attachment; filename=\"meeting-notes-" + stamp + ".zip\"")
                .body(body);
    }

    /** ids 解析：逗号分隔纯数字（去重保序）；缺失/空/含非数字/超 {@value #MAX_ZIP_IDS} → 400。 */
    static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw new ApiException(400, "INVALID_PARAM",
                    "ids 必填（逗号分隔的任务 id，最多 " + MAX_ZIP_IDS + " 个）");
        }
        List<Long> raw = new ArrayList<>();
        for (String part : ids.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("\\d+")) {
                throw new ApiException(400, "INVALID_PARAM", "ids 必须是数字任务 id（逗号分隔）");
            }
            raw.add(Long.parseLong(value));
        }
        if (raw.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM",
                    "ids 必填（逗号分隔的任务 id，最多 " + MAX_ZIP_IDS + " 个）");
        }
        if (raw.size() > MAX_ZIP_IDS) {
            throw new ApiException(400, "INVALID_PARAM", "ids 最多 " + MAX_ZIP_IDS + " 个任务");
        }
        return new ArrayList<>(new LinkedHashSet<>(raw));
    }

    /** 预检可导出 id（DONE 且 transcript 非空、本人）——只查元数据不拉全文；结果按请求顺序保序。 */
    static List<Long> filterExportable(List<Long> ids, long userId, JdbcTemplate jdbc) {
        String inSql = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(ids);
        List<Long> exportable = jdbc.query(
                "SELECT t.id FROM task t LEFT JOIN task_result r ON r.task_id = t.id "
                        + "WHERE t.user_id = ? AND t.id IN (" + inSql + ") "
                        + "AND t.status = 'DONE' AND r.transcript IS NOT NULL AND r.transcript <> ''",
                (rs, i) -> rs.getLong(1), args.toArray());
        List<Long> ordered = new ArrayList<>();
        for (Long id : ids) {
            if (exportable.contains(id)) {
                ordered.add(id);
            }
        }
        return ordered;
    }

    /** 流式写 zip：遍历**完整请求 ids**逐条渲染（预检剔除/竞态删除 → 跳过记 errors），
     *  末尾附 {@code _errors.txt}（有跳过时才有）。 */
    static void streamZip(OutputStream out, List<Long> ids, long userId, JdbcTemplate jdbc,
                          ObjectMapper mapper, Set<String> sections) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            List<String> errors = new ArrayList<>();
            for (Long id : ids) {
                TaskExportController.RenderedExport rendered =
                        TaskExportController.render(id, userId, jdbc, mapper, sections);
                if (rendered.skipReason() != null) {
                    errors.add(id + ": " + rendered.skipReason());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(rendered.entryName()));
                zip.write(rendered.markdown().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (!errors.isEmpty()) {
                zip.putNextEntry(new ZipEntry("_errors.txt"));
                zip.write(String.join("\n", errors).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** zip 条目名：{@code <id>-<安全文件名>.md}——文件名取 source_uri 尾段，去扩展名、替换非法字符、
     *  超长保留尾部；无 source_uri → task。 */
    static String zipEntryName(long id, String sourceUri) {
        String name = "task";
        if (sourceUri != null && !sourceUri.isBlank()) {
            int slash = sourceUri.lastIndexOf('/');
            name = slash >= 0 ? sourceUri.substring(slash + 1) : sourceUri;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            name = name.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_").trim();
        }
        if (name.isBlank()) {
            name = "task";
        }
        if (name.length() > MAX_ENTRY_BASE_LEN) {
            name = name.substring(name.length() - MAX_ENTRY_BASE_LEN);
        }
        return id + "-" + name + ".md";
    }
}
