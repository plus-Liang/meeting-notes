package com.meetingnotes.web;

import com.meetingnotes.common.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 账号端点（T4.37a，全部 /api/v1/auth/）：
 * <ul>
 *   <li>POST /register {username,password} —— 首次注册认领管理员 / 受注册开关控制；</li>
 *   <li>POST /login {username,password} —— 单令牌轮换；</li>
 *   <li>POST /logout —— **仍需认证**（Bearer），清空当前账号 token_hash；</li>
 *   <li>GET /me —— 当前账号信息。</li>
 * </ul>
 * register/login 由 ApiTokenFilter 豁免（两条精确路径，K-46 口径：豁免面只减不加前缀）。
 * 响应统一 {token, user_id, username, role}（logout {revoked:true}，me 不含 token）。
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

    @PostMapping("/logout")
    @ResponseBody
    public Map<String, Object> logout() {
        authService.logout(UserContext.current());
        return Map.of("revoked", true);
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
