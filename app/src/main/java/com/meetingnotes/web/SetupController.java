package com.meetingnotes.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * T4.93 C3 首启向导端点（免鉴权面见 {@link ApiTokenFilter} 三条精确豁免，POST 走 register 限流桶）。
 * <ul>
 *   <li>GET /api/v1/setup/status：needs_setup + 五项自检（{@link SetupChecks#runAll}）；</li>
 *   <li>POST /api/v1/setup/admin：body {username,password,setup_code} → 唯一认领入口
 *       {@link AuthService#setupClaimAdmin}；成功返回会话（user_id/username/role/token）；</li>
 *   <li>GET /api/v1/setup/model：透传 ASR /health 的 model/ready（不抛 500，5s 超时）；</li>
 *   <li>GET /setup 与 /setup.html：向导页。A4 方案①（D-241 建议）：controller 显式映射，
 *       优先级高于静态资源 handler——adminClaimed()==true 时抛 {@link NoResourceFoundException}
 *       → 404（响应体 = 全站通用 404 口径，不暴露「已完成」字样）；未初始化时回源
 *       classpath:static/setup.html（静态页不含数据，数据全由上面三个接口给）。</li>
 * </ul>
 */
@RestController
public class SetupController {

    private final AuthService authService;
    private final SetupChecks setupChecks;

    public SetupController(AuthService authService, SetupChecks setupChecks) {
        this.authService = authService;
        this.setupChecks = setupChecks;
    }

    /** setup_code 字段由 Jackson SNAKE_CASE 全局策略映射（D-041 口径）。 */
    public record SetupAdminRequest(String username, String password, String setupCode) {
    }

    @GetMapping("/api/v1/setup/status")
    public Map<String, Object> status() throws NoResourceFoundException {
        requireUnclaimed();
        return Map.of("needs_setup", true, "checks", setupChecks.runAll());
    }

    @PostMapping("/api/v1/setup/admin")
    public Map<String, Object> createAdmin(@RequestBody(required = false) SetupAdminRequest body)
            throws NoResourceFoundException {
        requireUnclaimed();
        AuthService.AuthSession session = authService.setupClaimAdmin(
                body == null ? null : body.username(),
                body == null ? null : body.password(),
                body == null ? null : body.setupCode());
        return Map.of("user_id", session.userId(), "username", session.username(),
                "role", session.role(), "token", session.token());
    }

    @GetMapping("/api/v1/setup/model")
    public Map<String, Object> model() throws NoResourceFoundException {
        requireUnclaimed();
        return setupChecks.asrModelStatus();
    }

    /** A4：向导页仅未初始化可达（/setup 与 /setup.html 两条都显式接管）。 */
    @GetMapping({"/setup", "/setup.html"})
    public ResponseEntity<byte[]> page() throws NoResourceFoundException {
        requireUnclaimed();
        try {
            byte[] html = new ClassPathResource("static/setup.html").getInputStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html);
        } catch (Exception e) {
            throw new NoResourceFoundException(HttpMethod.GET, "/setup.html");
        }
    }

    /** 404 语义（设计稿 §3.1）：判据只认 adminClaimed()，与登录逻辑同源；不暴露「已完成」。 */
    private void requireUnclaimed() throws NoResourceFoundException {
        if (authService.adminClaimed()) {
            throw new NoResourceFoundException(HttpMethod.GET, "/setup.html");
        }
    }
}
