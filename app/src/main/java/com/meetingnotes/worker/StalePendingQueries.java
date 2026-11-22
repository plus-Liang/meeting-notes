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

    /** T4.67 S4：陈旧 PENDING 阈值的单一来源——启动恢复与运行期清扫两条路径共用（默认 2 分钟）。 */
    public static final long DEFAULT_STALE_MINUTES = 2;

    private final JdbcTemplate jdbc;

    public StalePendingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 陈旧 PENDING id 列表：status='PENDING' 且 updated_at 早于 cutoff。SQL 与原两处逐字一致。
     *
     * <p>T4.67 S8（出队顺序）：ORDER BY updated_at DESC（最新在前）+ 调用方逐条 leftPush（头插）=
     * 后压入的排更前 → 最先出队的是 updated_at 最旧的行 → 「先卡住的更早被处理」（先来先服务）。
     * 若把 DESC 改成 ASC，leftPush 会把最旧的压到队尾、最新的压到队头，处理顺序整体反转。
     */
    public List<Long> findStalePending(Timestamp cutoff) {
        return jdbc.query("SELECT id FROM task WHERE status = 'PENDING' AND updated_at < ?"
                        + " ORDER BY updated_at DESC",
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
