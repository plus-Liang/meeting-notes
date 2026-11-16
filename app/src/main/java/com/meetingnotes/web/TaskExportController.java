package com.meetingnotes.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导出 Markdown（T3.7 / US-2；T3.9 从 {@link TaskController} 整体抽出；T4.17 增「要点」节；
 * T4.18 增 include 选择）：节顺序固定（摘要/关键词/要点/转写/待办），只输出被选中的节。
 * include 合法值固定 5 个；未知值 → 400 INVALID_PARAM（消息列合法值）；省略 → 默认
 * summary,keywords,keypoints,transcript（**默认不含待办**，观察期）；include 为空/全空 → 400。
 * format 目前只支持 md；任务不存在 → 404；无内容（未 DONE/transcript 空）→ 400。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskExportController {

    /** 认证接入前固定单用户（与 TaskController 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    /** include 合法值（固定节序）；由 {@link TaskMarkdownExporter} 的节常量派生，防两处漂移。 */
    private static final List<String> VALID_INCLUDE = List.of(TaskMarkdownExporter.SECTION_SUMMARY,
            TaskMarkdownExporter.SECTION_KEYWORDS, TaskMarkdownExporter.SECTION_KEYPOINTS,
            TaskMarkdownExporter.SECTION_TRANSCRIPT, TaskMarkdownExporter.SECTION_TODOS);

    private final JdbcTemplate jdbc;
    /** F-10：导出解析用的单例 ObjectMapper（此前每行 new，属技术债）。 */
    private final ObjectMapper mapper;

    public TaskExportController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<String> export(@PathVariable("id") long id,
                                         @RequestParam(name = "format", required = false) String format,
                                         @RequestParam(name = "include", required = false) String include) {
        if (!"md".equals(format)) {
            throw new ApiException(400, "INVALID_PARAM", "format 目前只支持 md");
        }
        Set<String> sections = parseInclude(include);
        record ExportRow(String status, String transcript, String summary,
                         String keywords, String todos, String keypoints, String transcriptPunctuated) {
        }
        List<ExportRow> rows = jdbc.query("""
                SELECT t.status,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript,
                       r.summary, r.keywords::text AS keywords, r.todos::text AS todos,
                       r.keypoints::text AS keypoints, r.transcript_punctuated
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE t.id = ? AND t.user_id = ?
                """, (rs, i) -> new ExportRow(rs.getString("status"), rs.getString("transcript"),
                rs.getString("summary"), rs.getString("keywords"), rs.getString("todos"),
                rs.getString("keypoints"), rs.getString("transcript_punctuated")),
                id, DEFAULT_USER_ID);
        if (rows.isEmpty()) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        ExportRow row = rows.get(0);
        if (row.transcript() == null || row.transcript().isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "任务没有可导出的内容（未完成或无转写文本）");
        }
        List<String> keywords = null;
        List<TodoItemView> todos = null;
        List<KeypointView> keypoints = null;
        try {
            if (row.keywords() != null && !row.keywords().isBlank()) {
                keywords = mapper.readValue(row.keywords(), new TypeReference<List<String>>() { });
            }
            JsonNode todosNode = row.todos() == null || row.todos().isBlank()
                    ? null : mapper.readTree(row.todos());
            if (todosNode != null && todosNode.isArray()) {
                todos = TodoItemView.listFrom(todosNode);
            }
            JsonNode kpNode = row.keypoints() == null || row.keypoints().isBlank()
                    ? null : mapper.readTree(row.keypoints());
            if (kpNode != null && kpNode.isArray()) {
                keypoints = KeypointView.listFrom(kpNode);
            }
        } catch (Exception e) {
            keywords = null;
            todos = null;
            keypoints = null;
        }
        String markdown = TaskMarkdownExporter.export(sections, row.summary(), keywords, keypoints,
                transcriptForExport(row.transcriptPunctuated(), row.transcript()), todos);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"task-" + id + ".md\"")
                .body(markdown);
    }

    /**
     * include 解析（T4.18）：参数**省略**（null） → 默认 summary,keywords,keypoints,transcript
     * （待办观察期默认不含）；**传了但为空/全空（逗号/空白）** → 400；未知值 → 400（消息列合法值）。
     * 返回按**固定节序**排列的选择集。
     */
    static Set<String> parseInclude(String include) {
        if (include == null) {
            return new LinkedHashSet<>(List.of(TaskMarkdownExporter.SECTION_SUMMARY,
                    TaskMarkdownExporter.SECTION_KEYWORDS, TaskMarkdownExporter.SECTION_KEYPOINTS,
                    TaskMarkdownExporter.SECTION_TRANSCRIPT));
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String part : include.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!VALID_INCLUDE.contains(value)) {
                throw new ApiException(400, "INVALID_PARAM",
                        "include 只允许 " + String.join(",", VALID_INCLUDE));
            }
            picked.add(value);
        }
        if (picked.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM",
                    "include 不能为空，合法值：" + String.join(",", VALID_INCLUDE));
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String key : VALID_INCLUDE) {
            if (picked.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    /**
     * 导出转写节文本（T4.18）：有 AI 标点文本则按句换行（。！？!? 切句、保留标点在句尾）；
     * 无则原样返回原始转写。空 → 空串（节内写（无）由导出器处理）。
     */
    static String transcriptForExport(String punctuated, String raw) {
        String source = punctuated != null && !punctuated.isBlank() ? punctuated : raw;
        if (source == null || source.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            current.append(c);
            if ("。！？!?".indexOf(c) >= 0) {
                appendSentence(out, current);
            }
        }
        if (!current.isEmpty()) {
            appendSentence(out, current);
        }
        return out.toString().trim();
    }

    private static void appendSentence(StringBuilder out, StringBuilder sentence) {
        String trimmed = sentence.toString().trim();
        if (!trimmed.isEmpty()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        sentence.setLength(0);
    }
}
