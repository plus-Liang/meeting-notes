package com.meetingnotes.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 术语表读写（T3.5 / D-037 表已存在：UNIQUE(user_id, wrong_text)）。
 *
 * <p>upsert 语义（契约定死）：冲突时 **hits + 1、right_text 更新为最新、priority 保持原值**——
 * priority 是 T3.6 注入排序的持久属性，不随本次改词波动；不冲突时插入 hits=1 / priority=0。
 * 参数化 SQL，禁止拼字符串。
 */
@Repository
public class GlossaryRepository {

    private final JdbcTemplate jdbc;

    public GlossaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(String wrongText, String rightText, int hits) {
    }

    /** upsert 语句（package-private 供单测断言形状：ON CONFLICT 累加 + priority 不动）。 */
    static final String UPSERT_SQL = """
            INSERT INTO glossary (user_id, wrong_text, right_text, hits, priority)
            VALUES (?, ?, ?, 1, 0)
            ON CONFLICT (user_id, wrong_text)
            DO UPDATE SET hits = glossary.hits + 1, right_text = EXCLUDED.right_text
            RETURNING hits
            """;

    /** 沉淀一条改词；返回 upsert 之后的最新 hits（首次为 1，重复改递增）。 */
    public Entry upsert(long userId, String wrongText, String rightText) {
        Integer hits = jdbc.queryForObject(UPSERT_SQL, Integer.class, userId, wrongText, rightText);
        return new Entry(wrongText, rightText, hits == null ? 1 : hits);
    }
}
