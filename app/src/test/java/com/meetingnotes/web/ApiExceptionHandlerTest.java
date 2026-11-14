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

    /* ---- T2.12 / D-057：框架 4xx 不再被吞成 500 ---- */

    @Test
    void frameworkErrorResponseKeepsItsStatusWithGenericCode() {
        // Spring 的 ErrorResponseException（404 路径类）→ 404 NOT_FOUND，不再 500
        ResponseEntity<ErrorResponse> resp = handler.onUnexpected(
                new org.springframework.web.ErrorResponseException(org.springframework.http.HttpStatus.NOT_FOUND));

        assertEquals(404, resp.getStatusCode().value());
        assertEquals("NOT_FOUND", resp.getBody().code());
    }

    @Test
    void methodNotSupportedMapsTo405() {
        ResponseEntity<ErrorResponse> resp = handler.onUnexpected(
                new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE"));

        assertEquals(405, resp.getStatusCode().value());
        assertEquals("METHOD_NOT_ALLOWED", resp.getBody().code());
    }

    @Test
    void unsupportedMediaTypeMapsTo415() {
        ResponseEntity<ErrorResponse> resp = handler.onUnexpected(
                new org.springframework.web.HttpMediaTypeNotSupportedException("application/json"));

        assertEquals(415, resp.getStatusCode().value());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", resp.getBody().code());
    }

    @Test
    void typeMismatchMapsTo400InvalidParam() {
        ResponseEntity<ErrorResponse> resp = handler.onUnexpected(
                new org.springframework.beans.TypeMismatchException("abc", Long.class));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("INVALID_PARAM", resp.getBody().code());
    }
}
