package com.meetingnotes.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.common.ShareTokens;
import com.meetingnotes.task.ShareService;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 只读分享端点（T4.28 / D-136，K-46）：
 * <ul>
 *   <li>{@code POST /api/v1/tasks/{id}/share}（需 token）：生成 32B SecureRandom base64url token，
 *       DB 只存 SHA256 hex；返回 {task_id, token, url, expires_at}；非 DONE → 400；404 口径同删除端点；</li>
 *   <li>{@code DELETE /api/v1/tasks/{id}/share}（需 token）：撤销该任务全部未撤销链接 → {revoked:N}；</li>
 *   <li>{@code GET /api/v1/share/{token}}：**匿名可访问**（ApiTokenFilter 只豁免此一条前缀）——
 *       有效/未撤销/未过期 → 12 键只读子集；无效/过期/撤销统一 404 {@code SHARE_NOT_FOUND}（防探测）。
 *       匿名者拿不到列表、拿不到其它任务、没有任何写能力。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ShareController {


    private final ShareService shareService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 创建响应（token 明文只在本次响应出现）。 */
    public record ShareCreatedResponse(long task_id, String token, String url, OffsetDateTime expires_at) {
    }

    /** 撤销响应。 */
    public record ShareRevokedResponse(int revoked) {
    }

    /** 匿名只读视图：12 键固定子集（详情 14 键不动，这是独立新契约）。 */
    public record SharedTaskView(long task_id, String original_name, Long duration_sec, OffsetDateTime created_at,
                                 String summary, List<String> keywords, List<KeypointView> keypoints,
                                 List<TodoItemView> todos, String transcript, String transcript_punctuated,
                                 String ai_tier, String template) {
    }

    /** 匿名查询行（T4.28 提升为类级：测试可构造）。 */
    record SharedRow(long taskId, String sourceUri, Long durationSec, OffsetDateTime createdAt,
                     String summary, String keywords, String todos, String keypoints,
                     String transcript, String transcriptPunctuated, String aiTier, String template) {
    }

    public ShareController(ShareService shareService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.shareService = shareService;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping("/tasks/{id}/share")
    public ShareCreatedResponse create(@PathVariable("id") long id) {
        ShareService.ShareCreated created = shareService.create(UserContext.current(), id);
        return new ShareCreatedResponse(created.taskId(), created.token(), created.url(), created.expiresAt());
    }

    @DeleteMapping("/tasks/{id}/share")
    public ShareRevokedResponse revoke(@PathVariable("id") long id) {
        return new ShareRevokedResponse(shareService.revokeAll(UserContext.current(), id));
    }

    /** K-46：匿名只读——按 SHA256(hash) 查有效链接，不存在/撤销/过期统一 404 SHARE_NOT_FOUND（不区分原因）。 */
    @GetMapping("/share/{token}")
    public SharedTaskView view(@PathVariable("token") String token) {
        String hash = ShareTokens.sha256Hex(token);
        if (!shareService.existsActiveByHash(hash)) {
            throw new ApiException(404, "SHARE_NOT_FOUND", "分享链接无效或已过期");
        }
        List<SharedRow> rows = jdbc.query("""
                SELECT t.id AS task_id, t.source_uri, t.duration_sec, t.created_at,
                       r.summary, r.keywords::text AS keywords, r.todos::text AS todos,
                       r.keypoints::text AS keypoints, r.transcript, r.transcript_punctuated,
                       r.ai_tier, r.template
                FROM share_link s
                JOIN task t ON t.id = s.task_id
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                """, (rs, i) -> new SharedRow(rs.getLong("task_id"), rs.getString("source_uri"),
                rs.getObject("duration_sec") == null ? null : rs.getLong("duration_sec"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("summary"),
                rs.getString("keywords"), rs.getString("todos"), rs.getString("keypoints"),
                rs.getString("transcript"), rs.getString("transcript_punctuated"),
                rs.getString("ai_tier"), rs.getString("template")), hash);
        if (rows.isEmpty()) {
            throw new ApiException(404, "SHARE_NOT_FOUND", "分享链接无效或已过期");
        }
        SharedRow row = rows.get(0);
        /* JSONB 解析容错口径同详情/导出：坏 JSON → 空集合，不炸匿名接口 */
        List<String> keywords = List.of();
        List<TodoItemView> todos = List.of();
        List<KeypointView> keypoints = List.of();
        try {
            if (row.keywords() != null && !row.keywords().isBlank()) {
                List<String> parsed = mapper.readValue(row.keywords(), new TypeReference<List<String>>() { });
                if (parsed != null) keywords = parsed;
            }
            JsonNode todosNode = row.todos() == null || row.todos().isBlank() ? null : mapper.readTree(row.todos());
            if (todosNode != null && todosNode.isArray()) {
                List<TodoItemView> parsedTodos = TodoItemView.listFrom(todosNode);
                if (parsedTodos != null) todos = parsedTodos;
            }
            JsonNode kpNode = row.keypoints() == null || row.keypoints().isBlank() ? null : mapper.readTree(row.keypoints());
            if (kpNode != null && kpNode.isArray()) {
                List<KeypointView> parsedKp = KeypointView.listFrom(kpNode);
                if (parsedKp != null) keypoints = parsedKp;
            }
        } catch (Exception e) {
            keywords = List.of();
            todos = List.of();
            keypoints = List.of();
        }
        String sourceUri = row.sourceUri();
        String originalName = "任务 " + row.taskId();
        if (sourceUri != null && sourceUri.contains("/")) {
            originalName = sourceUri.substring(sourceUri.lastIndexOf('/') + 1);
        }
        return new SharedTaskView(row.taskId(), originalName, row.durationSec(), row.createdAt(),
                row.summary(), keywords, keypoints, todos, row.transcript(), row.transcriptPunctuated(),
                row.aiTier(), row.template());
    }
}
