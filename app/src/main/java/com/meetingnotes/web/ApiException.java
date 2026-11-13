package com.meetingnotes.web;

/**
 * 业务性拒绝：由 {@link ApiExceptionHandler} 统一转成
 * {"code": "...", "message": "..."} 结构（spec N6 / N8 / T1.4 卡片）。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
