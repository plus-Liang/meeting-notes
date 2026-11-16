package com.meetingnotes.task;

import com.meetingnotes.ai.StructuringService;
import com.meetingnotes.common.ApiException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 换模板重跑（T4.26 / F-17）：同一任务用**已存 transcript** 重跑结构化，换模板出新摘要/关键词/待办/要点。
 * 边界：不重跑 ASR、不新建任务、不扣任务配额（不触任何配额表）。
 *
 * <p>事务（TransactionTemplate 显式包裹）+ 并发：先 {@code SELECT ... FOR UPDATE NOWAIT} 锁任务行——
 * 第二个并发请求拿不到锁立即 {@code CannotAcquireLockException} → 409 {@code TASK_BUSY}（快速失败，不排队）；
 * 拿到锁后读写状态与 transcript（result 行同事务再锁），全程同一事务：结构化失败抛异常 → 回滚，
 * 旧结果 + 旧模板原样保留，**不写半截**。LLM 调用在事务内持行锁（v1 单用户，冲突面只有并发重跑本身）。
 *
 * <p>错误口径：不存在/非本人 → 404 {@code TASK_NOT_FOUND}；非 DONE → 409 {@code TASK_BUSY}；
 * 空/空转写 → 400 {@code INVALID_PARAM}；结构化降级链耗尽 → 500 {@code INTERNAL_ERROR}（明确说明已保留原结果）。
 */
@Service
public class RestructureService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StructuringService structuringService;

    /** 重跑响应（snake_case：task_id/template/ai_tier，前端拿后刷新详情）。 */
    public record RestructureResponse(long taskId, String template, String aiTier) {
    }

    public RestructureService(JdbcTemplate jdbc, TransactionTemplate tx, StructuringService structuringService) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.structuringService = structuringService;
    }

    /** template 已由 controller 经 {@link TemplateResolver#validateExplicit} 校验并规范化（非空）。 */
    public RestructureResponse restructure(long userId, long taskId, String template) {
        RestructureResponse resp = tx.execute(status -> restructureInTx(userId, taskId, template));
        return resp == null
                ? new RestructureResponse(taskId, template, null) : resp;
    }

    private RestructureResponse restructureInTx(long userId, long taskId, String template) {
        String status;
        try {
            status = jdbc.query("SELECT status FROM task WHERE id = ? AND user_id = ? FOR UPDATE NOWAIT",
                    rs -> rs.next() ? rs.getString(1) : null, taskId, userId);
        } catch (CannotAcquireLockException e) {
            throw new ApiException(409, "TASK_BUSY", "该任务正被其他操作锁定（可能在重新生成中），请稍后再试");
        }
        if (status == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
        if (!"DONE".equals(status)) {
            throw new ApiException(409, "TASK_BUSY", "任务未完成（" + status + "），只有 DONE 任务可以按模板重新生成");
        }
        String transcript = jdbc.query("SELECT transcript FROM task_result WHERE task_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
        if (transcript == null || transcript.isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "该任务没有可重新生成的转写内容");
        }
        boolean ok = structuringService.structure(taskId, transcript, template);
        if (!ok) {
            throw new ApiException(500, "INTERNAL_ERROR", "重新生成失败（结构化未产出结果），已保留原摘要/关键词/待办/要点与模板");
        }
        String aiTier = jdbc.queryForObject("SELECT ai_tier FROM task_result WHERE task_id = ?", String.class, taskId);
        return new RestructureResponse(taskId, template, aiTier);
    }
}
