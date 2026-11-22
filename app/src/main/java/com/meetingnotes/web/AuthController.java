package com.meetingnotes.web;

import com.meetingnotes.common.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 账号端点（T4.37a，全部 /api/v1/auth/）：
 * <ul>
 *   <li>POST /register {username,password} —— 首次注册认领管理员 / 受注册开关控制；</li>
 *   <li>POST /login {username,password} —— 签发新会话（T4.71 多设备并存）；</li>
 *   <li>POST /logout —— **仍需认证**（Bearer），只失效当前令牌的会话（T4.71）；</li>
 *   <li>GET /me —— 当前账号信息；</li>
 *   <li>GET /sessions —— 会话列表（默认本人；?user_id= 他人 → 必须 ADMIN，T4.71 C3）。</li>
 * </ul>
 * register/login 由 ApiTokenFilter 豁免（两条精确路径，K-46 口径：豁免面只减不加前缀）。
 * 响应统一 {token, user_id, username, role}（logout {revoked:true}，me/sessions 不含 token）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record CredentialsRequest(String username, String password) {
    }

    @PostMapping("/register")
    @ResponseBody
    public Map<String, Object> register(@RequestBody(required = false) CredentialsRequest body) {
        AuthService.AuthSession session = authService.register(
                body == null ? null : body.username(), body == null ? null : body.password());
        return sessionView(session);
    }

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> login(@RequestBody(required = false) CredentialsRequest body) {
        AuthService.AuthSession session = authService.login(
                body == null ? null : body.username(), body == null ? null : body.password());
        return sessionView(session);
    }

    /** T4.71：logout 只失效当前令牌的会话——控制器把当前 Bearer 原样传给服务层（服务层自行哈希匹配）。 */
    @PostMapping("/logout")
    @ResponseBody
    public Map<String, Object> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(UserContext.current(), bearerToken(authorization));
        return Map.of("revoked", true);
    }

    /** T4.71 C3：会话列表——默认本人；?user_id= 他人 → 必须 ADMIN（非本人非 ADMIN → 403 FORBIDDEN）。
     *  项只含 id/created_at/last_used_at（ISO-8601），不含令牌/hash 任何形态。 */
    @GetMapping("/sessions")
    @ResponseBody
    public List<Map<String, Object>> sessions(
            @RequestParam(name = "user_id", required = false) Long userId) {
        long self = UserContext.current();
        long target = userId == null ? self : userId;
        if (target != self) {
            authService.requireAdmin(self);
        }
        return authService.listSessions(target).stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.id(),
                        "created_at", s.createdAt().toString(),
                        "last_used_at", s.lastUsedAt().toString()))
                .toList();
    }

    private static String bearerToken(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : "";
    }

    @GetMapping("/me")
    @ResponseBody
    public Map<String, Object> me() {
        AuthService.UserInfo info = authService.me(UserContext.current());
        return Map.of("user_id", info.userId(), "username", info.username(), "role", info.role());
    }

    /** T4.70 C2：注册状态（免鉴权——ApiTokenFilter 精确豁免 GET /api/v1/auth/status）。
     *  只暴露两个布尔：登录页据此决定是否显示「创建账号」入口；不含用户名/哈希等任何用户信息。 */
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        return Map.of(
                "registrationEnabled", authService.registrationEnabled(),
                "adminClaimed", authService.adminClaimed());
    }

    private static Map<String, Object> sessionView(AuthService.AuthSession session) {
        return Map.of("token", session.token(), "user_id", session.userId(),
                "username", session.username(), "role", session.role());
    }
}
