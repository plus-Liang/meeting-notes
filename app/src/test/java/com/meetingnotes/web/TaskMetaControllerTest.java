package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.TaskMetaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.29：meta controller 校验矩阵——labels 非数组/超 10/21 字/非字符串/重复去重、favorite 非 bool、两键全缺 400。 */
@ExtendWith(MockitoExtension.class)
class TaskMetaControllerTest {

    @Mock
    private TaskMetaService service;

    private TaskMetaController newController() {
        return new TaskMetaController(service);
    }

    private com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s);
    }

    @Test
    void labelsValidationMatrix() throws Exception {
        // 11 个标签 → 400
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 1; i <= 11; i++) {
            if (i > 1) eleven.append(',');
            eleven.append('"').append("标签").append(i).append('"');
        }
        eleven.append(']');
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json(eleven.toString()))).getStatus());

        // 21 字 → 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json("[\"" + "字".repeat(21) + "\"]"))).getStatus());

        // 空字符串（trim 后 0 字）→ 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json("{\"labels\":[\"  \"]}"))).getStatus());

        // 非数组 → 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json("{\"labels\":\"课程\"}"))).getStatus());

        // 元素非字符串 → 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json("{\"labels\":[42]}"))).getStatus());

        // favorite 非 bool → 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json("{\"favorite\":\"yes\"}"))).getStatus());

        // 两键全缺 → 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> newController().update(7L, json("{}"))).getStatus());
    }

    @Test
    void labelsDedupeTrimsPreservingOrderAndPassesToService() throws Exception {
        when(service.update(eq(1L), eq(7L), eq(java.util.List.of("课程", "重点")), isNull()))
                .thenReturn(new TaskMetaService.TaskMeta(7L, java.util.List.of("课程", "重点"), false));

        TaskMetaService.TaskMeta meta =
                newController().update(7L, json("{\"labels\":[\" 课程 \",\"重点\",\"课程\"]}"));

        assertEquals(java.util.List.of("课程", "重点"), meta.labels(), "trim + 去重保序");
        verify(service).update(eq(1L), eq(7L), eq(java.util.List.of("课程", "重点")), isNull());
    }

    @Test
    void favoriteOnlyUpdatePassesNullLabels() throws Exception {
        when(service.update(eq(1L), eq(7L), isNull(), eq(true)))
                .thenReturn(new TaskMetaService.TaskMeta(7L, java.util.List.of(), true));

        TaskMetaService.TaskMeta meta = newController().update(7L, json("{\"favorite\":true}"));

        assertTrue(meta.favorite());
        verify(service).update(eq(1L), eq(7L), isNull(), eq(true));
    }
}
