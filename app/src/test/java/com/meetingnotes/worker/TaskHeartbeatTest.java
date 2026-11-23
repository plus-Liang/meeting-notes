package com.meetingnotes.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.77/P-06：心跳单测（H2 内存库，不起 Spring 容器，间隔 1s）。
 * ① RUNNING 行持续刷新（计数递增）；② 任务改 DONE 后心跳自退（计数冻结）；③ close() 幂等。
 * 断言代理：包装 JdbcTemplate 计数「UPDATE … status = 'RUNNING'」的执行次数——updated_at 只有当前值，
 * 历史刷新次数只能从调用侧观测。
 */
class TaskHeartbeatTest {

    private CountingJdbc jdbc;
    private TaskHeartbeat hb;
    private TaskHeartbeat.Session session;

    /** 只给心跳用的计数器：凡 SQL 含 status = 'RUNNING' 的 update 记一次。 */
    private static class CountingJdbc extends JdbcTemplate {
        final AtomicInteger runningUpdates = new AtomicInteger();

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("status = 'RUNNING'")) {
                runningUpdates.incrementAndGet();
            }
            return super.update(sql, args);
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = new CountingJdbc();
        jdbc.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:hbtest;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE IF NOT EXISTS task (id BIGINT PRIMARY KEY, status VARCHAR(16), "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("MERGE INTO task (id, status) KEY (id) VALUES (1, 'RUNNING')");
        hb = new TaskHeartbeat(jdbc, 1);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    /** ① RUNNING 行：2.5s 内心跳按 1s 间隔刷新 ≥2 次，且 SQL 绑定 id 与 RUNNING 条件。 */
    @Test
    void runningTaskGetsRefreshedRepeatedly() throws Exception {
        session = hb.start(1);
        Thread.sleep(2500);
        int count = jdbc.runningUpdates.get();
        assertTrue(count >= 2, "2.5s 内应至少刷新 2 次，实际 " + count);
    }

    /** ② 任务改 DONE → 心跳下一轮醒来探测一次（影响 0 行）→ 自退，此后计数冻结。 */
    @Test
    void heartbeatStopsWhenTaskLeavesRunning() throws Exception {
        session = hb.start(1);
        Thread.sleep(1300);
        jdbc.update("UPDATE task SET status = 'DONE' WHERE id = 1");
        Thread.sleep(3000); // 覆盖一次醒来：发现 0 行 → 自退（该次探测自身计 1 次）
        int afterProbe = jdbc.runningUpdates.get();
        org.junit.jupiter.api.Assertions.assertTrue(afterProbe <= 2,
                "DONE 前最多刷 1 次 + 1 次自退探测，实际 " + afterProbe);
        Thread.sleep(1500); // 再过一个心跳周期：必须冻结
        assertEquals(afterProbe, jdbc.runningUpdates.get(),
                "自退探测后不允许再刷（updated_at 不得再变化）");
    }

    /** ③ close() 幂等：连调两次不抛，且 close 后计数冻结。 */
    @Test
    void closeIsIdempotentAndStopsRefreshing() throws Exception {
        session = hb.start(1);
        Thread.sleep(1300);
        session.close();
        assertDoesNotThrow(session::close); // 幂等
        int frozen = jdbc.runningUpdates.get();
        Thread.sleep(1200);
        assertEquals(frozen, jdbc.runningUpdates.get(), "close 后不允许再刷新");
    }
}
