package com.meetingnotes.worker;

import com.meetingnotes.task.TaskTrashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T4.96/C9（v0.2 回收站，D-245）：回收站到期自动清理——独立于 {@link StalePendingSweeper}（两件事，不合并）。
 * 每轮 {@code purgeExpired(200)}（上限 200 条，防一次扫爆，D-245 S4）；**启动时再跑一次**
 * （ApplicationReadyEvent，保证不常驻的部署也能清）。{@code enabled=false} 时整轮跳过（不查库）。
 * 到期判定与时间戳传参在 {@link com.meetingnotes.task.TaskTrashService#purgeExpired}（Java 侧算 cutoff，禁 PG interval）。
 * 日志键 {@code purged}/{@code retentionDays}（grep 取证用）。
 */
@Component
public class TaskTrashSweeper {

    private static final Logger log = LoggerFactory.getLogger(TaskTrashSweeper.class);

    /** 每轮最多清理条数（D-245 S4）。 */
    static final int PURGE_BATCH = 200;

    /** retention-days 兜底值（与 TaskTrashService 同口径：<1 WARN 回退 30）。 */
    static final int DEFAULT_RETENTION_DAYS = 30;

    private final TaskTrashService taskTrashService;
    private final boolean enabled;
    private final int retentionDays;

    public TaskTrashSweeper(TaskTrashService taskTrashService,
                            @Value("${app.trash.purge-enabled:true}") boolean enabled,
                            @Value("${app.trash.retention-days:30}") int configuredRetentionDays) {
        this.taskTrashService = taskTrashService;
        this.enabled = enabled;
        int days = configuredRetentionDays;
        if (days < 1) {
            log.warn("TRASH_RETENTION_DAYS={} 非法（必须 >= 1，0 等于一次误配永久删除），回退到 {} 天",
                    days, DEFAULT_RETENTION_DAYS);
            days = DEFAULT_RETENTION_DAYS;
        }
        this.retentionDays = days;
    }

    /** 周期清理；enabled=false 时整轮跳过（不查库）。 */
    @Scheduled(fixedDelayString = "${app.trash.purge-interval-ms:3600000}")
    public void sweepExpiredTrash() {
        if (!enabled) {
            return;
        }
        runOnce("周期");
    }

    /** 启动时再跑一次（不常驻的部署也能清）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        if (!enabled) {
            log.info("回收站自动清理已关闭（TRASH_PURGE_ENABLED=false），启动清扫跳过");
            return;
        }
        runOnce("启动");
    }

    private void runOnce(String phase) {
        int purged;
        try {
            purged = taskTrashService.purgeExpired(PURGE_BATCH);
        } catch (org.springframework.dao.DataAccessException e) {
            // 无表（无 key 启动 / 测试 H2）或 DB 瞬时故障：跳过本轮，绝不拖垮启动/调度线程（D-035 口径，同 StartupRecovery）
            log.warn("回收站到期清扫跳过（{}：可能无表或数据库不可用）：{}", phase, e.getMessage());
            return;
        }
        log.atInfo()
                .addKeyValue("purged", purged)
                .addKeyValue("retentionDays", retentionDays)
                .log("回收站到期清扫（{}）：完成", phase);
    }
}
