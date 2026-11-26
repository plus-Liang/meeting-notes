package com.meetingnotes.worker;

import com.meetingnotes.task.DeadTaskRecorder;
import com.meetingnotes.webhook.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T4.94/P-03：进程级崩溃（SIGKILL/OOM）不经过 {@code markFailure}，attempts 永远是 0
 * ⇒ 毒丸任务崩-重启-崩无限循环、永远进不了 DEAD。本类只在「确证消费者已死」的两处被调用：
 * 启动恢复的「陈旧 RUNNING」（{@link StartupRecovery}）与运行期僵尸回执清扫（{@link StalePendingSweeper}）。
 * <p>计数与状态迁移原子：一条可移植条件 UPDATE 同时做 {@code status='PENDING'} + {@code attempts=attempts+1}，
 * {@code WHERE id=? AND status='RUNNING'} ⇒ 影响行数 0 = 别人已处理，直接跳过（保 T4.77-fix B1 / T4.79 防双抢语义）。
 * 达到 {@code app.task.max-retries}（默认 3，与业务失败共用同一上限）走
 * {@link DeadTaskRecorder#markDead}（DEAD 唯一合法入口，不变量 DEAD⟺quota_refunded，D-234/T4.87）
 * 并通知 webhook 终态。
 * <p>不做重投（publish）——头插/尾插顺序语义属于调用方（T4.62 S2：启动期头插、运行期尾插，不许合并）。
 */
@Component
public class TaskCrashRecovery {

    private static final Logger log = LoggerFactory.getLogger(TaskCrashRecovery.class);

    /** recover 结果：SKIPPED=别人已处理/行不存在；NEED_REQUEUE=行已 PENDING（attempts 已 +1）待调用方重投；DEAD=已达上限转死信。 */
    public enum Outcome { SKIPPED, NEED_REQUEUE, DEAD }

    private final JdbcTemplate jdbc;
    private final TaskAttempts taskAttempts;
    private final DeadTaskRecorder deadTaskRecorder;
    private final WebhookDeliveryService webhookService;
    private final int maxRetries;

    public TaskCrashRecovery(JdbcTemplate jdbc, TaskAttempts taskAttempts,
                             DeadTaskRecorder deadTaskRecorder, WebhookDeliveryService webhookService,
                             @Value("${app.task.max-retries:3}") int maxRetries) {
        this.jdbc = jdbc;
        this.taskAttempts = taskAttempts;
        this.deadTaskRecorder = deadTaskRecorder;
        this.webhookService = webhookService;
        this.maxRetries = maxRetries;
    }

    /** 崩溃回收：原子「PENDING + attempts+1」，达上限置 DEAD。source 仅用于日志与死信原因（startup / sweeper）。 */
    public Outcome recover(long taskId, String source) {
        int won = jdbc.update("UPDATE task SET status = 'PENDING', attempts = attempts + 1, updated_at = now() "
                + "WHERE id = ? AND status = 'RUNNING'", taskId);
        if (won == 0) {
            return Outcome.SKIPPED; // 别人已处理（或行不存在）：不做任何其它写入
        }
        Integer attempts = taskAttempts.read(taskId);
        if (attempts == null) {
            return Outcome.SKIPPED;
        }
        if (attempts >= maxRetries) {
            deadTaskRecorder.markDead(taskId, "WORKER_CRASH",
                    "崩溃计次达上限（source=" + source + "，attempts=" + attempts + "）");
            log.atError()
                    .addKeyValue("taskId", taskId)
                    .addKeyValue("source", source)
                    .addKeyValue("attempts", attempts)
                    .log("崩溃计次达上限，置 DEAD");
            webhookService.onTerminal(taskId, "DEAD"); // 终态异步投递 Webhook（内部不抛、不阻塞）
            return Outcome.DEAD;
        }
        log.atDebug()
                .addKeyValue("taskId", taskId)
                .addKeyValue("attempts", attempts)
                .log("崩溃回收：任务回 PENDING 待重投");
        return Outcome.NEED_REQUEUE; // 行已 PENDING 且 updated_at=now()，头插/尾插由调用方决定
    }
}
