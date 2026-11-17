package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.ShareTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 只读分享链接（T4.28 / D-136）：为 DONE 任务生成 32 字节 SecureRandom token（base64url），
 * **DB 只存 SHA256 hex**（K-46：明文不落库，防拖库即泄露）；默认有效期 {@code SHARE_TTL_DAYS:7} 天。
 * 生成不扣配额（只读能力，不是新任务）；撤销 = 该任务全部未撤销链接置 revoked_at（软删，可审计）。
 *
 * <p>错误口径：任务不存在/非本人 → 404 {@code TASK_NOT_FOUND}（同删除端点）；
 * 非 DONE → 400 {@code INVALID_PARAM}（只有完成的任务可分享）。
 * 匿名读取（GET /api/v1/share/{token}）在 web/ShareController，无效/过期/撤销统一 404 {@code SHARE_NOT_FOUND} 防探测。
 */
@Service
public class ShareService {

    private final JdbcTemplate jdbc;
    private final int ttlDays;

    /** 创建结果（token 明文只在本次响应出现一次）。 */
    public record ShareCreated(long taskId, String token, String url, OffsetDateTime expiresAt) {
    }

    public ShareService(JdbcTemplate jdbc,
                        @Value("${app.share.ttl-days:7}") int ttlDays) {
        this.jdbc = jdbc;
        this.ttlDays = ttlDays;
    }

    public ShareCreated create(long userId, long taskId) {
        String status = jdbc.query("SELECT status FROM task WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId, userId);
        if (status == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
        if (!"DONE".equals(status)) {
            throw new ApiException(400, "INVALID_PARAM", "任务未完成（" + status + "），只有 DONE 任务可以创建分享链接");
        }
        String token = ShareTokens.generate();
        String hash = ShareTokens.sha256Hex(token);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(ttlDays);
        jdbc.update("INSERT INTO share_link (task_id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                taskId, userId, hash, expiresAt);
        return new ShareCreated(taskId, token, "/share.html?token=" + token, expiresAt);
    }

    /** 撤销该任务全部未撤销链接；@return 撤销条数。任务不存在/非本人 → 404（口径同删除端点）。 */
    public int revokeAll(long userId, long taskId) {
        Integer present = jdbc.query("SELECT 1 FROM task WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? 1 : null, taskId, userId);
        if (present == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
        return jdbc.update("UPDATE share_link SET revoked_at = now() WHERE task_id = ? AND revoked_at IS NULL", taskId);
    }

    /** token hash 是否有效（存在、未撤销、未过期）——供 web 层匿名读取前判定。 */
    public boolean existsActiveByHash(String hash) {
        List<Long> rows = jdbc.query("""
                SELECT id FROM share_link
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()
                """, (rs, i) -> rs.getLong(1), hash);
        return !rows.isEmpty();
    }
}
