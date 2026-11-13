package com.meetingnotes.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 统一错误出口：所有业务拒绝都走 {"code","message"} 结构（T1.4 卡片）。
 * N8：429 必须带 Retry-After。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> onApiException(ApiException e) {
        if ("QUOTA_EXCEEDED".equals(e.getCode())) {
            return ResponseEntity.status(e.getStatus())
                    .header("Retry-After", String.valueOf(secondsUntilMidnight()))
                    .body(new ErrorResponse(e.getCode(), e.getMessage()));
        }
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(e.getCode(), e.getMessage()));
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
