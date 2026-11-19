package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.TaskRenameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.43：重命名 controller 校验矩阵——正常 / 1 字 / 120 字 / 121 字 / 空串清除 / 缺字段 /
 * 错类型 / body 空；归属与不存在 404 在 service（此处验证异常透传）。
 */
@ExtendWith(MockitoExtension.class)
class TaskRenameControllerTest {

    @Mock
    private TaskRenameService service;

    private TaskRenameController newController() {
        return new TaskRenameController(service);
    }

    private com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s);
    }

    @BeforeEach
    void setUpUserContext() {
        com.meetingnotes.common.UserContext.set(1L);
    }

    @AfterEach
    void clearUserContext() {
        com.meetingnotes.common.UserContext.clear();
    }

    @Test
    void normalCaseTrimsAndPassesThrough() throws Exception {
        when(service.rename(1L, 7L, "周会 纪要")).thenReturn(new TaskRenameService.RenameResult(7L, "周会 纪要"));

        assertEquals(new TaskRenameService.RenameResult(7L, "周会 纪要"),
                newController().rename(7L, json("{\"display_name\":\"  周会 纪要  \"}")));
        verify(service).rename(1L, 7L, "周会 纪要");
    }

    @Test
    void boundary1And120CharsTrimmed() throws Exception {
        when(service.rename(eq(1L), eq(7L), eq("名"))).thenReturn(new TaskRenameService.RenameResult(7L, "名"));
        assertEquals("名", newController().rename(7L, json("{\"display_name\":\" 名 \"}")).displayName());

        String exactly120 = "字".repeat(120);
        when(service.rename(eq(1L), eq(7L), eq(exactly120)))
                .thenReturn(new TaskRenameService.RenameResult(7L, exactly120));
        assertEquals(120, newController().rename(7L, json("{\"display_name\":\"" + exactly120 + "\"}"))
                .displayName().length(), "恰好 120 字合法");
    }

    @Test
    void over121CharsFails400() throws Exception {
        ApiException e = assertThrows(ApiException.class,
                () -> newController().rename(7L, json("{\"display_name\":\"" + "字".repeat(121) + "\"}")));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }

    @Test
    void emptyStringMeansClearPassesNullToService() throws Exception {
        when(service.rename(eq(1L), eq(7L), isNull()))
                .thenReturn(new TaskRenameService.RenameResult(7L, null));
        assertNull(newController().rename(7L, json("{\"display_name\":\"\"}")).displayName(),
                "空串 = 清除（落 NULL），service 收到 null");
        verify(service).rename(1L, 7L, null);
    }

    @Test
    void missingFieldNullBodyAndWrongTypeFail400() throws Exception {
        // body 为 null
        ApiException noBody = assertThrows(ApiException.class, () -> newController().rename(7L, null));
        // 缺字段
        ApiException noField = assertThrows(ApiException.class,
                () -> newController().rename(7L, json("{\"other\":1}")));
        // 非字符串（数字）
        ApiException wrongType = assertThrows(ApiException.class,
                () -> newController().rename(7L, json("{\"display_name\":123}")));
        // 非字符串（对象）
        ApiException objType = assertThrows(ApiException.class,
                () -> newController().rename(7L, json("{\"display_name\":{}}")));
        assertEquals(400, noBody.getStatus());
        assertEquals(400, noField.getStatus());
        assertEquals(400, wrongType.getStatus());
        assertEquals(400, objType.getStatus());
        assertEquals("INVALID_PARAM", wrongType.getCode());
    }

    @Test
    void service404PropagatesUnchecked() throws Exception {
        when(service.rename(eq(1L), eq(99L), eq("新名")))
                .thenThrow(new ApiException(404, "TASK_NOT_FOUND", "任务 99 不存在"));
        ApiException e = assertThrows(ApiException.class,
                () -> newController().rename(99L, json("{\"display_name\":\"新名\"}")));
        assertEquals(404, e.getStatus(), "不存在/非本人统一 404（controller 不吞）");
        assertEquals("TASK_NOT_FOUND", e.getCode());
    }
}
