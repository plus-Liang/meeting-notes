package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 统一错误出口：所有业务拒绝都走 {"code","message"} 结构（T1.4 卡片）。
 * N8：429 必须带 Retry-After。T2.11：Exception 兜底 → 500 INTERNAL_ERROR（不外泄堆栈/内部类名）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String REQUEST_REJECTED_MSG = "请求无法被处理，请检查路径、方法与参数";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> onApiException(ApiException e, HttpServletRequest request) {
        if ("QUOTA_EXCEEDED".equals(e.getCode())) {
            return ResponseEntity.status(e.getStatus())
                    .header("Retry-After", String.valueOf(secondsUntilMidnight()))
                    .body(new ErrorResponse(e.getCode(), e.getMessage()));
        }
        // T4.80/P-29：限流拒绝也带 Retry-After——秒数取拦截器写入的请求属性（RateLimitInterceptor.RETRY_AFTER_ATTR），
        // 拿不到（理论不可能，防御性兜底）默认 "60"；QUOTA_EXCEEDED 既有行为不变（沿用 secondsUntilMidnight）。
        if ("RATE_LIMITED".equals(e.getCode())) {
            Object retryAfterSec = request.getAttribute(RateLimitInterceptor.RETRY_AFTER_ATTR);
            return ResponseEntity.status(e.getStatus())
                    .header("Retry-After", retryAfterSec != null ? retryAfterSec.toString() : "60")
                    .body(new ErrorResponse(e.getCode(), e.getMessage()));
        }
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    /**
     * 兜底（T2.12 / D-057）：框架自身异常按其语义返回，只有真未知异常才 500 INTERNAL_ERROR。
     * 通用码分层：INVALID_PARAM(400) / NOT_FOUND(404) / METHOD_NOT_ALLOWED(405) / UNSUPPORTED_MEDIA_TYPE(415)。
     * 响应体只带固定文案，不透传异常 detail（防泄漏堆栈/SQL）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode())
                    .body(new ErrorResponse(genericCodeFor(er.getStatusCode().value()), REQUEST_REJECTED_MSG));
        }
        // 部分框架异常未实现 ErrorResponse 接口，显式映射（Spring 6：这四类是最常见的客户端错误）
        if (e instanceof org.springframework.web.HttpRequestMethodNotSupportedException) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(new ErrorResponse("METHOD_NOT_ALLOWED", REQUEST_REJECTED_MSG));
        }
        if (e instanceof org.springframework.web.HttpMediaTypeNotSupportedException) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", REQUEST_REJECTED_MSG));
        }
        if (e instanceof org.springframework.web.servlet.resource.NoResourceFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("NOT_FOUND", REQUEST_REJECTED_MSG));
        }
        if (e instanceof org.springframework.beans.TypeMismatchException
                || e instanceof org.springframework.web.bind.MissingServletRequestParameterException
                || e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("INVALID_PARAM", REQUEST_REJECTED_MSG));
        }
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务器内部错误，请稍后重试；若持续出现请联系管理员"));
    }

    /** 框架异常 HTTP 状态 → 通用错误码（D-057 分层，未列举的状态归 INTERNAL_ERROR，不发明新码）。 */
    private static String genericCodeFor(int statusCode) {
        return switch (statusCode) {
            case 400 -> "INVALID_PARAM";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
        };
    }

    /** multipart 大小超限（servlet 层先拦截，磁盘不会被灌满）→ 413 SIZE_EXCEEDED（D-041）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> onTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("SIZE_EXCEEDED", "文件超过大小上限"));
    }

    /** multipart 解析失败（非 multipart 请求、表单坏掉等）。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> onMultipart(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_MEDIA", "请求必须是 multipart/form-data 且携带 file 字段"));
    }

    /** file 字段缺失。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> onMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_MEDIA", "multipart 字段 file 缺失"));
    }

    private static long secondsUntilMidnight() {
        LocalDateTime next = LocalDate.now().plusDays(1).atTime(LocalTime.MIDNIGHT);
        return Math.max(1, Duration.between(LocalDateTime.now(), next).getSeconds());
    }
}
