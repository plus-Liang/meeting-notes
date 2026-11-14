package com.meetingnotes.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** T2.11：兜底异常 handler —— 500 INTERNAL_ERROR，响应体不外泄异常细节。 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedExceptionMapsTo500InternalErrorWithoutLeakingDetails() {
        ResponseEntity<ErrorResponse> resp =
                handler.onUnexpected(new RuntimeException("jdbc://secret-table boom at InternalDao:42"));

        assertEquals(500, resp.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", resp.getBody().code());
        assertFalse(resp.getBody().message().contains("boom"), "不得外泄异常消息");
        assertFalse(resp.getBody().message().contains("jdbc"), "不得外泄内部细节");
    }

    @Test
    void apiExceptionStillWinsOverFallback() {
        ApiException e = new ApiException(413, "SIZE_EXCEEDED", "文件超过大小上限");
        ResponseEntity<ErrorResponse> resp = handler.onApiException(e);

        assertEquals(413, resp.getStatusCode().value());
        assertEquals("SIZE_EXCEEDED", resp.getBody().code());
    }
}
