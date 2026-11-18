package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.6：GlossaryTermSource——排序（SQL 形状）、上限 20、总长 400 截断、空表返回空串。
 * 排序在 SQL 里（LIMIT 由 DB 做），Java 侧另做防御性上限；真库行为留给实机。
 */
@ExtendWith(MockitoExtension.class)
class GlossaryTermSourceTest {

    @Mock
    private JdbcTemplate jdbc;

    private GlossaryTermSource newSource() {
        return new GlossaryTermSource(jdbc);
    }

    private void stubRows(String... pairs) {
        List<GlossaryTermSource.Term> terms = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            terms.add(new GlossaryTermSource.Term(pairs[i], pairs[i + 1]));
        }
        when(jdbc.query(contains("FROM glossary"), any(RowMapper.class), any(), anyInt()))
                .thenReturn(terms);
    }

    @Test
    void sqlOrdersByHitsPriorityAndCapsWithLimit() {
        when(jdbc.query(contains("FROM glossary"), any(RowMapper.class), any(), anyInt()))
                .thenReturn(List.of());
        newSource().inject(1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowMapper>any(), any(), anyInt());
        assertTrue(sql.getValue().contains("ORDER BY hits DESC, priority DESC, wrong_text ASC"),
                "排序口径必须在 SQL 里，实际：" + sql.getValue());
        assertTrue(sql.getValue().contains("LIMIT ?"), "条数上限走 SQL LIMIT");
    }

    @Test
    void emptyTableReturnsEmptyInjection() {
        when(jdbc.query(contains("FROM glossary"), any(RowMapper.class), any(), anyInt()))
                .thenReturn(List.of());

        GlossaryTermSource.GlossaryInjection injection = newSource().inject(1L);

        assertEquals("", injection.text(), "空表返回空串（不注入、不报错）");
        assertEquals(0, injection.termCount());
    }

    @Test
    void capsAtTwentyTermsEvenIfStorageReturnsMore() {
        // 防御性上限：即使存储层多给（stub 30 条），Java 侧也只取 20
        stubRows(IntStream.range(0, 30).mapToObj(i -> new String[]{"词" + i, "正" + i})
                .flatMap(p -> java.util.stream.Stream.of(p[0], p[1])).toArray(String[]::new));

        GlossaryTermSource.GlossaryInjection injection = newSource().inject(1L);

        assertEquals(20, injection.termCount(), "上限 20 条");
    }

    @Test
    void truncatesAtFourHundredChars() {
        // 每条约 20 字 × 20 条 ≈ 460 字 > 400 → 按序截断，总长不得超 400
        stubRows(IntStream.range(0, 20).mapToObj(i -> new String[]{"错误说法甲乙丙丁" + i, "正确写法甲乙丙丁" + i})
                .flatMap(p -> java.util.stream.Stream.of(p[0], p[1])).toArray(String[]::new));

        GlossaryTermSource.GlossaryInjection injection = newSource().inject(1L);

        assertTrue(injection.text().length() <= 400, "总长 ≤400 字，实际 " + injection.text().length());
        assertTrue(injection.termCount() < 20, "400 字截断后不足 20 条，实际 " + injection.termCount());
        assertTrue(injection.text().startsWith("【术语表（原文说法 → 正确写法）】"), "注入段可读格式");
    }
}
