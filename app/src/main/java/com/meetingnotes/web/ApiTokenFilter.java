package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * API 认证（T4.37a 双凭据）：/api/v1/** 必须带 Authorization: Bearer &lt;凭据&gt;。
 * <ul>
 *   <li>Bearer = {@code API_TOKEN}（常量时间比较）→ userId=1（1 号账号 = 管理员座位，
 *       既有接入契约零破坏）；</li>
 *   <li>否则按**账号令牌**处理：SHA-256 hex 查 {@code app_user.token_hash} → 该用户 id；</li>
 *   <li>都不匹配 → 401 UNAUTHORIZED（消息指引两种凭据）。</li>
 * </ul>
 *
 * <p>豁免面（filter 只挂 /api/v1 前缀，其余一律放行）：/actuator/**、静态页与资源、
 * /v3/api-docs/**、/swagger-ui/**。T4.28（K-46）：/api/v1/share/ 前缀（匿名只读分享）；
 * T4.35b：GET ^/api/v1/tasks/\d+/audio$（自带 HMAC 短时签名验签）；
 * T4.69 C1：GET /api/v1/version（构建标识，version/build 两个非敏感字段）；
 * **T4.37a 只加两条精确路径**：POST /api/v1/auth/login、POST /api/v1/auth/register
 * （/auth/logout、/auth/me 仍要认证）。
 *
 * <p>认证成功写 {@link UserContext}（ThreadLocal）→ finally 必须 clear()。
 * 401 走统一错误口径（ErrorResponse + 全局 snake_case，D-041/D-057）。
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    static final String UNAUTHORIZED_CODE = "UNAUTHORIZED";
    private static final String UNAUTHORIZED_MESSAGE =
            "缺少或无效的凭据：请带 Authorization: Bearer <账号令牌或 API_TOKEN>";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final long ADMIN_USER_ID = 1L;

    private final String apiToken;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApiTokenFilter(@Value("${app.security.api-token}") String apiToken,
                          JdbcTemplate jdbc,
                          ObjectMapper mapper) {
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 只守 /api/v1/**：actuator、静态页、OpenAPI、Swagger 全部豁免（K：豁免面逐条实测）。
     *  T4.28（K-46）：匿名只读分享——额外放行 /api/v1/share/ 这**一条**前缀（GET share/{token}）；
     *  其它 /api/v1/**（列表/详情/写操作）对匿名与分享 token 仍一律 401。
     *  T4.35b：匿名音频流——只放行 GET 且**精确**匹配 {@code ^/api/v1/tasks/\d+/audio$} 这一条。
     *  T4.37a：账号端点只豁免 POST /api/v1/auth/login 与 POST /api/v1/auth/register 两条**精确**
     *  路径（/auth/logout、/auth/me 仍要认证）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/v1/share/")) {
            return true;
        }
        if ("GET".equals(request.getMethod()) && uri.matches("^/api/v1/tasks/\\d+/audio$")) {
            return true; // T4.35b：音频流自带短时签名，验签在 controller（失败 404，绝不回退成无签名下载）
        }
        if ("GET".equals(request.getMethod()) && "/api/v1/version".equals(uri)) {
            return true; // T4.69 C1：构建版本端点免鉴权（只含 version/build 两个非敏感字段）
        }
        if ("POST".equals(request.getMethod())
                && ("/api/v1/auth/login".equals(uri) || "/api/v1/auth/register".equals(uri))) {
            return true; // T4.37a：注册/登录入口（其余 /auth/** 仍要认证）
        }
        return !uri.startsWith("/api/v1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long userId = authenticate(request.getHeader("Authorization"));
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(WWW_AUTHENTICATE, "Bearer");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            mapper.writeValue(response.getWriter(), new ErrorResponse(UNAUTHORIZED_CODE, UNAUTHORIZED_MESSAGE));
            return;
        }
        try {
            UserContext.set(userId);
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /** 双凭据判定：API_TOKEN → 1；账号令牌 → app_user.token_hash 对应 id；都不匹配 → null。
     *  只认精确 "Bearer " 前缀（RFC 写法；小写 bearer 与既有契约一致按 401 处理）。 */
    private Long authenticate(String header) {
        String trimmed = header == null ? "" : header.trim();
        if (!trimmed.startsWith(BEARER_PREFIX)) {
            return null;
        }
        if (!trimmed.isEmpty() && constantTimeEquals(BEARER_PREFIX + apiToken, trimmed)) {
            return ADMIN_USER_ID;
        }
        if (apiToken.isEmpty()) {
            return null; // fail-closed：API_TOKEN 为空串时拒绝所有（既有机型口径不变）
        }
        String token = trimmed.substring(BEARER_PREFIX.length());
        if (token.isEmpty() || token.getBytes(StandardCharsets.UTF_8).length > 256) {
            return null; // 形状护栏：超长串不做哈希查询
        }
        String hash = sha256Hex(token);
        List<Long> ids = jdbc.query("SELECT id FROM app_user WHERE token_hash = ?",
                (rs, i) -> rs.getLong(1), hash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 常量时间比较（长度不同立即假，内容比较不短路）。 */
    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
