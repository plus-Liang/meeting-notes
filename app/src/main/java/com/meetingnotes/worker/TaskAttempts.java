package com.meetingnotes.worker;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T4.94/P-03：attempts 的可移植读写原语。
 * <p>唯一目的：让 attempts 读写可在 H2 真库测试——替换 {@code TranscriptionWorker.markFailure}
 * 原来的 {@code UPDATE … RETURNING attempts}（PG 专用语法，H2 跑不了，那段 SQL 在单测里从未被执行，
 * 审计 P-03 的「测试有效性缺口」）。只做原语，不加业务判断（重试上限 / DEAD 判定归调用方）。
 */
@Component
public class TaskAttempts {

    private final JdbcTemplate jdbc;

    public TaskAttempts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** attempts + 1（顺带刷新 updated_at）；返回影响行数（0 = 行不存在，不抛）。 */
    public int bump(long taskId) {
        return jdbc.update("UPDATE task SET attempts = attempts + 1, updated_at = now() WHERE id = ?", taskId);
    }

    /** 读 attempts；行不存在返回 null。 */
    public Integer read(long taskId) {
        return jdbc.query("SELECT attempts FROM task WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, taskId);
    }
}
