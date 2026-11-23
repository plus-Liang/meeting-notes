package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T4.77/P-06：转写期间的「心跳」——认领成功后每 heartbeatSec 秒刷一次**本任务行**的 updated_at，
 * 让 P-01 的僵尸判定（RUNNING 且 updated_at 超阈值）不会误伤正常在跑的长音频任务。
 *
 * <p>设计约束（本卡禁止项 2）：只刷自己认领的那个 id（{@code WHERE id = ? AND status = 'RUNNING'}），
 * <b>绝不做全局 {@code @Scheduled} 扫全表 RUNNING</b>——多实例下那会把别的实例正在跑的任务刷成
 * 「新鲜」，直接把僵尸判定废掉。UPDATE 影响 0 行 = 任务已不在 RUNNING（终态或被回收），心跳自退。
 *
 * <p>心跳任何异常只 log.debug，绝不影响转写主流程；线程 daemon、名字带 taskId 便于排查。
 */
@Component
public class TaskHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TaskHeartbeat.class);

    private final JdbcTemplate jdbc;
    private final long heartbeatSec;

    public TaskHeartbeat(JdbcTemplate jdbc,
                         @Value("${app.worker.heartbeat-sec:20}") long heartbeatSec) {
        this.jdbc = jdbc;
        this.heartbeatSec = heartbeatSec;
    }

    /** 心跳会话：{@link #close()} 幂等（置停止位 + interrupt + join）。实现 AutoCloseable 便于扩展。 */
    public interface Session extends AutoCloseable {
        @Override
        void close();
    }

    public Session start(long taskId) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            while (!stopped.get()) {
                try {
                    Thread.sleep(heartbeatSec * 1000L);
                } catch (InterruptedException e) {
                    return; // close() 的 interrupt，正常退出路径
                }
                try {
                    int updated = jdbc.update(
                            "UPDATE task SET updated_at = now() WHERE id = ? AND status = 'RUNNING'", taskId);
                    if (updated == 0) {
                        log.debug("心跳自退：任务 {} 已不在 RUNNING", taskId);
                        return;
                    }
                    log.debug("心跳已刷新：任务 {}（间隔 {}s）", taskId, heartbeatSec);
                } catch (RuntimeException e) {
                    log.debug("心跳刷新失败（不影响转写）：任务 {}", taskId, e);
                }
            }
        }, "worker-heartbeat-" + taskId);
        t.setDaemon(true);
        t.start();
        return new Session() {
            @Override
            public void close() {
                if (stopped.compareAndSet(false, true)) {
                    t.interrupt();
                    try {
                        t.join(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
    }
}
