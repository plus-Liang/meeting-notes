package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Token 认证（T4.6 / US-10 / D-040）：/api/v1/** 必须带 Authorization: Bearer <token>。
 *
 * <p>豁免面（filter 只挂 /api/v1 前缀，其余一律放行）：/actuator/**、静态页与资源、
 * /v3/api-docs/**、/swagger-ui/**——运维探活与契约发现不被认证打死。
 * token 读已有配置键 app.security.api-token（← API_TOKEN），不新增键；
 * 比较用 MessageDigest.isEqual 常量时间，不用 String.equals（防时序侧信道）。
 * 401 走统一错误口径（ErrorResponse + 全局 snake_case，D-041/D-057）。
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    static final String UNAUTHORIZED_CODE = "UNAUTHORIZED";
    private static final String UNAUTHORIZED_MESSAGE = "缺少或无效的 API Token：请带 Authorization: Bearer <API_TOKEN>";

    private final String apiToken;
    private final ObjectMapper mapper;

    public ApiTokenFilter(@Value("${app.security.api-token}") String apiToken, ObjectMapper mapper) {
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.mapper = mapper;
    }

    /** 只守 /api/v1/**：actuator、静态页、OpenAPI、Swagger 全部豁免（K：豁免面逐条实测）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = "Bearer " + apiToken;
        String header = request.getHeader("Authorization");
        if (apiToken.isEmpty() || header == null || !constantTimeEquals(expected, header.trim())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(WWW_AUTHENTICATE, "Bearer");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            mapper.writeValue(response.getWriter(), new ErrorResponse(UNAUTHORIZED_CODE, UNAUTHORIZED_MESSAGE));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 常量时间比较（长度不同立即假，内容比较不短路）。 */
    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
