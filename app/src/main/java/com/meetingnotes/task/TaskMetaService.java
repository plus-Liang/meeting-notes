package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务标签/收藏（T4.29 / D-137）：PATCH 更新（labels jsonb / favorite），GET 读回（供详情页编辑初值）。
 * 校验（≤10 个、1–20 字、去重保序、布尔类型）在 controller；service 只做存在性 + 落库 + 回读。
 * labels 用 jsonb 数组存储（禁逗号拼字符串，卡口径）；未提供的键不动（COALESCE 保留原值）。
 * 404 口径同删除端点（不存在/非本人）。
 */
@Service
public class TaskMetaService {

    private final JdbcTemplate jdbc;

    /** meta 更新/读取响应（snake_case）。 */
    public record TaskMeta(long taskId, List<String> labels, boolean favorite) {
    }

    /** 读取行（T4.29 提升为类级：测试可构造）。 */
    record MetaRow(String labels, boolean favorite) {
    }

    public TaskMetaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** labels/favorite 传 null = 该键本次不更新。返回更新后的完整 meta。 */
    public TaskMeta update(long userId, long taskId, List<String> labels, Boolean favorite) {
        ensureExists(userId, taskId);
        jdbc.update("""
                UPDATE task SET labels = COALESCE(?::jsonb, labels), favorite = COALESCE(?, favorite)
                WHERE id = ? AND user_id = ?
                """, labels == null ? null : labelsJson(labels), favorite, taskId, userId);
        return read(userId, taskId);
    }

    public TaskMeta read(long userId, long taskId) {
        ensureExists(userId, taskId);
        List<MetaRow> rows = jdbc.query(
                "SELECT labels::text AS labels, favorite FROM task WHERE id = ? AND user_id = ?",
                (rs, i) -> new MetaRow(rs.getString("labels"), rs.getBoolean("favorite")), taskId, userId);
        MetaRow row = rows.get(0);
        List<String> labels = List.of();
        try {
            if (row.labels() != null && !row.labels().isBlank()) {
                List<String> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(row.labels(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
                if (parsed != null) {
                    labels = parsed;
                }
            }
        } catch (Exception e) {
            labels = List.of(); /* 坏 JSON 降级为空集，口径同 keywords */
        }
        return new TaskMeta(taskId, labels, row.favorite());
    }

    private void ensureExists(long userId, long taskId) {
        Integer present = jdbc.query("SELECT 1 FROM task WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? 1 : null, taskId, userId);
        if (present == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
    }

    private String labelsJson(List<String> labels) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(labels);
        } catch (Exception e) {
            return "[]";
        }
    }
}
