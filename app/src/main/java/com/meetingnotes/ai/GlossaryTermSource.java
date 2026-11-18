package com.meetingnotes.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 术语注入源（T3.6 / US-3 闭环）：读 glossary（T3.5 沉淀的用户改词，D-037），
 * 按 {@code hits desc, priority desc, wrong_text asc} 取前 {@value #MAX_TERMS} 条，
 * 拼成一段可读的提示词注入文本（总长 ≤{@value #MAX_TOTAL_CHARS} 字，超了按序截断）。
 *
 * <p>空表/全空返回空串——结构化提示词与 T3.3 以来**逐字节一致**（回归判据：无词表不改变任何行为）。
 * 只负责"取与拼"，注入位置与文案在 {@link StructuringPrompt}；不打印词条内容到日志（隐私，卡 3）。
 */
@Component
public class GlossaryTermSource {

    /** 注入条数上限（卡 1）。 */
    static final int MAX_TERMS = 20;
    /** 注入文本总长上限（卡 1：≈400~500 tok，仍在 ⑦ 预算余量内）。 */
    static final int MAX_TOTAL_CHARS = 400;

    private final JdbcTemplate jdbc;

    public GlossaryTermSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 注入文本 + 实际生效条数（日志用，不暴露词条内容）。 */
    public record GlossaryInjection(String text, int termCount) {
        public static final GlossaryInjection EMPTY = new GlossaryInjection("", 0);
    }

    record Term(String wrong, String right) {
    }

    private static final String SELECT_SQL = """
            SELECT wrong_text, right_text FROM glossary WHERE user_id = ?
            ORDER BY hits DESC, priority DESC, wrong_text ASC LIMIT ?
            """;

    /** 指定用户的注入文本（T4.37a：显式 userId 入参——异步链路不许读 UserContext ThreadLocal）。 */
    public GlossaryInjection inject(long userId) {
        return injectText(userId);
    }

    GlossaryInjection injectText(long userId) {
        List<Term> rows = jdbc.query(SELECT_SQL,
                (rs, i) -> new Term(rs.getString("wrong_text"), rs.getString("right_text")),
                userId, MAX_TERMS);
        if (rows.isEmpty()) {
            return GlossaryInjection.EMPTY;
        }
        StringBuilder sb = new StringBuilder("【术语表（原文说法 → 正确写法）】");
        int kept = 0;
        for (Term term : rows) {
            if (kept >= MAX_TERMS) {
                break;
            }
            String piece = (kept == 0 ? "" : "；") + term.wrong() + "→" + term.right();
            if (sb.length() + piece.length() > MAX_TOTAL_CHARS) {
                break;
            }
            sb.append(piece);
            kept++;
        }
        return kept == 0 ? GlossaryInjection.EMPTY : new GlossaryInjection(sb.toString(), kept);
    }

    /** F-15（D-089）：EXTRACTIVE 确定性替换用的词条列表——与 inject **同一查询口径**；空表 → 空列表 → 输出逐字节不变。 */
    List<Term> terms(long userId) {
        return jdbc.query(SELECT_SQL,
                (rs, i) -> new Term(rs.getString("wrong_text"), rs.getString("right_text")),
                userId, MAX_TERMS);
    }
}
