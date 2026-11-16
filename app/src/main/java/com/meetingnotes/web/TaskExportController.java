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
import java.util.List;

/**
 * 导出 Markdown（T3.7 / US-2；T3.9 从 {@link TaskController} 整体抽出，行为逐字节不变；
 * T4.17 增「要点」节）：四节固定顺序（摘要/关键词/要点/待办），待办带 done 勾选状态。
 * format 目前只支持 md；任务不存在 → 404；无内容（未 DONE/transcript 空）→ 400。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskExportController {

    /** 认证接入前固定单用户（与 TaskController 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    private final JdbcTemplate jdbc;
    /** F-10：导出解析用的单例 ObjectMapper（此前每行 new，属技术债）。 */
    private final ObjectMapper mapper;

    public TaskExportController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<String> export(@PathVariable("id") long id,
                                         @RequestParam(name = "format", required = false) String format) {
        if (!"md".equals(format)) {
            throw new ApiException(400, "INVALID_PARAM", "format 目前只支持 md");
        }
        record ExportRow(String status, String transcript, String summary,
                         String keywords, String todos, String keypoints) {
        }
        List<ExportRow> rows = jdbc.query("""
                SELECT t.status,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript,
                       r.summary, r.keywords::text AS keywords, r.todos::text AS todos,
                       r.keypoints::text AS keypoints
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE t.id = ? AND t.user_id = ?
                """, (rs, i) -> new ExportRow(rs.getString("status"), rs.getString("transcript"),
                rs.getString("summary"), rs.getString("keywords"), rs.getString("todos"),
                rs.getString("keypoints")),
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
        String markdown = TaskMarkdownExporter.export(row.summary(), keywords, keypoints, todos);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"task-" + id + ".md\"")
                .body(markdown);
    }
}
