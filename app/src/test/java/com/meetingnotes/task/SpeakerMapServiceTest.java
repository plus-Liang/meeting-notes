package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.36：说话人标注校验矩阵（卡判据逐条）——正常 map / 非 DONE / 无 segments / 键非数字 /
 * 键越界 / 值空 / 值超 20 字 / 条目 >200 / 空 map 清除 / 不存在任务 404 / 非本人 404。
 * 断言状态码 + 错误码；落库 SQL 不碰其它列（只 speaker_map + created_at）。
 */
@ExtendWith(MockitoExtension.class)
class SpeakerMapServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private SpeakerMapService newService() {
        return new SpeakerMapService(jdbc);
    }

    private void stubTask(long taskId, String status, int segLen) {
        when(jdbc.query(contains("WHERE t.id = ? AND t.user_id = ?"),
                ArgumentMatchers.<ResultSetExtractor<Map<String, Object>>>any(), eq(taskId), eq(1L)))
                .thenReturn(Map.of("status", status, "seg_len", segLen));
    }

    @Test
    void normalMapPersistedWithNormalizedValues() {
        stubTask(552L, "DONE", 18);
        Map<String, String> in = new LinkedHashMap<>();
        in.put("3", " 说话人 1 ");
        in.put("17", "说话人 2");
        SpeakerMapService.SpeakerMapResponse r = newService().update(552L, 1L, in);
        assertEquals("说话人 1", r.speakerMap().get("3"), "值去首尾空白");
        assertEquals(2, r.speakerMap().size());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("speaker_map"), eq(552L), json.capture());
        assertTrue(json.getValue().contains("\"3\":\"说话人 1\""), "jsonb 文本保序：" + json.getValue());
    }

    @Test
    void unknownTaskOrNotOwner404() {
        when(jdbc.query(contains("WHERE t.id = ? AND t.user_id = ?"),
                ArgumentMatchers.<ResultSetExtractor<Map<String, Object>>>any(), eq(999L), eq(1L)))
                .thenReturn(null);
        ApiException e = assertThrows(ApiException.class,
                () -> newService().update(999L, 1L, Map.of("0", "A")));
        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
    }

    @Test
    void nonDoneTaskRejected400() {
        stubTask(60L, "RUNNING", 18);
        ApiException e = assertThrows(ApiException.class,
                () -> newService().update(60L, 1L, Map.of("0", "A")));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }

    @Test
    void noSegmentsRejected400() {
        stubTask(536L, "DONE", -1); // PASTE/旧任务：segments NULL → -1
        ApiException e = assertThrows(ApiException.class,
                () -> newService().update(536L, 1L, Map.of("0", "A")));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        stubTask(530L, "DONE", 0); // 空音频：segments []
        ApiException e2 = assertThrows(ApiException.class,
                () -> newService().update(530L, 1L, Map.of("0", "A")));
        assertEquals("INVALID_PARAM", e2.getCode());
    }

    @Test
    void invalidKeysRejected400() {
        stubTask(552L, "DONE", 18);
        for (String bad : new String[]{"abc", "-1", "18", "999", "3.5", ""}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> newService().update(552L, 1L, Map.of(bad, "A")),
                    "键非法必须 400：" + bad);
            assertEquals(400, e.getStatus(), bad);
            assertEquals("INVALID_PARAM", e.getCode(), bad);
        }
    }

    @Test
    void invalidValuesRejected400() {
        stubTask(552L, "DONE", 18);
        ApiException empty = assertThrows(ApiException.class,
                () -> newService().update(552L, 1L, Map.of("3", "   ")));
        assertEquals("INVALID_PARAM", empty.getCode());
        ApiException tooLong = assertThrows(ApiException.class,
                () -> newService().update(552L, 1L, Map.of("3", "一二三四五六七八九十一二三四五六七八九十一")));
        assertEquals("INVALID_PARAM", tooLong.getCode());
        ApiException nullVal = assertThrows(ApiException.class, () -> {
            Map<String, String> withNull = new LinkedHashMap<>();
            withNull.put("3", null); /* Map.of 不收 null，用 HashMap 模拟 Jackson 反序列化的 null 值 */
            newService().update(552L, 1L, withNull);
        });
        assertEquals("INVALID_PARAM", nullVal.getCode());
    }

    @Test
    void overLimitEntriesRejected400() {
        stubTask(552L, "DONE", 500);
        Map<String, String> big = new LinkedHashMap<>();
        for (int i = 0; i < 201; i++) {
            big.put(String.valueOf(i), "A");
        }
        ApiException e = assertThrows(ApiException.class, () -> newService().update(552L, 1L, big));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }

    @Test
    void emptyMapClearsToNull() {
        stubTask(552L, "DONE", 18);
        SpeakerMapService.SpeakerMapResponse r = newService().update(552L, 1L, Map.of());
        assertNull(r.speakerMap(), "{} = 清除 → 落 NULL（响应 speaker_map:null，文档定死口径）");
        verify(jdbc).update(contains("speaker_map = NULL"), eq(552L));
    }

    @Test
    void nullRequestBodyAlsoClears() {
        stubTask(552L, "DONE", 18);
        SpeakerMapService.SpeakerMapResponse r = newService().update(552L, 1L, null);
        assertNull(r.speakerMap());
        verify(jdbc).update(contains("speaker_map = NULL"), eq(552L));
    }

    @Test
    void updateSqlNeverTouchesOtherColumns() {
        stubTask(552L, "DONE", 18);
        newService().update(552L, 1L, Map.of("0", "说话人 1"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(552L), ArgumentMatchers.anyString());
        assertFalse(sql.getValue().contains("transcript"), "不得碰 transcript");
        assertFalse(sql.getValue().contains("summary"), "不得碰 summary");
        assertFalse(sql.getValue().contains("template"), "不得碰 template");
    }

    private static void assertTrue(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }

    private static void assertFalse(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
    }
}
