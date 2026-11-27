package com.meetingnotes.worker;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
     * 陈旧 PENDING id 列表：status='PENDING' 且 updated_at 早于 cutoff，按 updated_at ASC 排序（最旧在前）。
     *
     * <p>T4.67-fix F5（出队顺序，S8 改判）：最终处理顺序由「返回顺序 + 推入端方向 + worker 消费速度」
     * 共同决定（K-132：空闲 worker 会逐条即时取走，单看 SQL 排序方向必误判）——
     * <ul>
     *   <li>{@code StalePendingSweeper}（运行期）：按返回序逐条 {@code publishToTail}（rightPush 尾插）
     *       ⇒ 恢复任务按「旧→新」依次排在队尾，worker 从队头（BLPOP）取出 ⇒ 先卡住的先处理，
     *       且不插队（不越过清扫期间新提交的任务）；</li>
     *   <li>{@code StartupRecovery}（启动期）：先 touch 全部、再按返回序逐条 {@code tryPublish}（头插）
     *       ——启动期没有并发新任务，不构成插队（两调用方顺序语义各自保留，T4.62 S2）。</li>
     * </ul>
     * （T4.67 曾用 DESC 并注释为「先来先服务」，真栈实测空闲 worker 下是新者先——判据改 ASC、清扫改尾插。）
     */
    public List<Long> findStalePending(Timestamp cutoff) {
        return jdbc.query("SELECT id FROM task WHERE status = 'PENDING' AND updated_at < ?"
                        + " ORDER BY updated_at ASC",
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

    /** T4.79/P-02 + T4.100/C6：按 id 取任务状态、updated_at 与 next_retry_at（僵尸回执清扫用）；行不存在返回 null。
     *  next_retry_at 可空（历史行），用 HashMap 承载（Map.of 不接受 null）。参数化 Timestamp，H2 真库可跑。 */
    public Map<String, Object> findTaskStatus(long id) {
        return jdbc.query("SELECT status, updated_at, next_retry_at FROM task WHERE id = ?",
                (rs, i) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("updated_at", rs.getTimestamp("updated_at"));
                    row.put("next_retry_at", rs.getTimestamp("next_retry_at"));
                    return row;
                }, id).stream().findFirst().orElse(null);
    }
}
