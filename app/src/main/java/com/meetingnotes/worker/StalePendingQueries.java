package com.meetingnotes.worker;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * T4.62 S2：陈旧 PENDING 判据与刷新语句的单一实现（此前 StartupRecovery 与 StalePendingSweeper 各一份，R4）。
 *
 * <p>只提供原语；两个调用方**保留各自的顺序语义，不许合并**：
 * <ul>
 *   <li>{@code StalePendingSweeper}（运行期）：先投递成功、再 {@link #touch(long)}——投递失败绝不刷新 updated_at；</li>
 *   <li>{@code StartupRecovery}（启动期）：先 {@link #touch(long)} 全部、再逐条投递——失败靠下次启动兜底。</li>
 * </ul>
 * cutoff 用参数化时间戳（JVM 侧时钟）：JVM 与 PG 同宿主同时钟、TZ 一致（D-067），
 * 换来的可移植性让生产 SQL 能被 H2 真库测试覆盖（R3）。
 */
@Component
public class StalePendingQueries {

    private final JdbcTemplate jdbc;

    public StalePendingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 陈旧 PENDING id 列表：status='PENDING' 且 updated_at 早于 cutoff。SQL 与原两处逐字一致。 */
    public List<Long> findStalePending(Timestamp cutoff) {
        return jdbc.query("SELECT id FROM task WHERE status = 'PENDING' AND updated_at < ?",
                (rs, i) -> rs.getLong(1), cutoff);
    }

    /** 成功入队/启动回收后刷新 updated_at，避免下一轮重复入队。SQL 与原两处逐字一致。 */
    public void touch(long id) {
        jdbc.update("UPDATE task SET updated_at = now() WHERE id = ?", id);
    }

    /** 分钟数 → cutoff 时间戳（JVM 侧时钟）。 */
    public static Timestamp cutoff(long minutes) {
        return Timestamp.from(Instant.now().minus(Duration.ofMinutes(minutes)));
    }
}
