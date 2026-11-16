package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.RestructureService;
import com.meetingnotes.task.TemplateResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.26：controller 直测——模板缺失/空白 → 400 必填、非法值 → 400 D-085 固定文案、合法值透传 service。 */
@ExtendWith(MockitoExtension.class)
class RestructureControllerTest {

    @Mock
    private RestructureService service;

    private RestructureController newController() {
        return new RestructureController(service);
    }

    @Test
    void missingOrBlankTemplateReturns400Required() {
        ApiException e1 = assertThrows(ApiException.class,
                () -> newController().restructure(7L, new RestructureController.RestructureRequest(null)));
        assertEquals(400, e1.getStatus());
        assertEquals("INVALID_PARAM", e1.getCode());

        ApiException e2 = assertThrows(ApiException.class,
                () -> newController().restructure(7L, new RestructureController.RestructureRequest("   ")));
        assertEquals(400, e2.getStatus());
        verify(service, never()).restructure(any(long.class), eq(7L), any());
    }

    @Test
    void invalidTemplateReturns400WithD085FixedMessage() {
        ApiException e = assertThrows(ApiException.class,
                () -> newController().restructure(7L, new RestructureController.RestructureRequest("bogus")));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        assertEquals(com.meetingnotes.ai.TemplatePrompts.INVALID_MESSAGE, e.getMessage(),
                "非法模板错误文案必须逐字沿用 D-085 固定 message");
        verify(service, never()).restructure(any(long.class), eq(7L), any());
    }

    @Test
    void validTemplateDelegatesToService() {
        when(service.restructure(1L, 7L, "course"))
                .thenReturn(new RestructureService.RestructureResponse(7L, "course", "CLOUD"));

        RestructureService.RestructureResponse resp =
                newController().restructure(7L, new RestructureController.RestructureRequest("COURSE"));

        assertEquals(7L, resp.taskId());
        assertEquals("course", resp.template(), "大小写规范化后透传");
        verify(service).restructure(1L, 7L, "course");
    }
}
