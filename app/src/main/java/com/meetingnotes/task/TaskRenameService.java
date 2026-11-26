package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 任务重命名（T4.43 / D-160）：PATCH {@code /api/v1/tasks/{id}} 的落库层。
 * 校验（trim 后 1..120 字 / 空串=清除 / 缺字段 / 非字符串）在 controller（薄，先例同 TaskMetaService）；
 * service 只做存在性（404 口径同删除端点：不存在/**非本人**统一 404 TASK_NOT_FOUND，D-040）
 * + 落库 + 回读。
 *
 * <p>**只写** {@code display_name} 与 {@code updated_at} 两列，不动 source_uri/status 等任何其它列
 * （不改原文件名与溯源，卡口径）；清除落 {@code NULL}（不是空串，与「从未命名」同语义，前端回退展示）。
 */
@Service
public class TaskRenameService {

    /** 卡口径：display_name trim 后最长 120 字（VARCHAR(120)，V9）。 */
    public static final int MAX_DISPLAY_NAME_LEN = 120;

    private final JdbcTemplate jdbc;

    /** 重命名结果（snake_case）：display_name 为 null = 已清除（空串入参）。 */
    public record RenameResult(long taskId, String displayName) {
    }

    public TaskRenameService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 重命名：{@code displayName} 非 null = 新展示名（trim 后 1..120 字，已由 controller 校验）；
     * {@code null} = 清除（落 NULL）。只写 display_name + updated_at。
     */
    public RenameResult rename(long userId, long taskId, String displayName) {
        ensureExists(userId, taskId);
        jdbc.update(
                "UPDATE task SET display_name = ?, updated_at = now() WHERE id = ? AND user_id = ?",
                displayName, taskId, userId);
        return new RenameResult(taskId, displayName);
    }

    private void ensureExists(long userId, long taskId) {
        Integer present = jdbc.query("SELECT 1 FROM task WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? 1 : null, taskId, userId);
        if (present == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
    }
}
