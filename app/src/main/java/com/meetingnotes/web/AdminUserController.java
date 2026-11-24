package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * T4.70 C3：管理员建号与成员列表（注册默认关闭后的唯一加人通道；先例：TaskDeleteController 独立成类）。
 * 两个端点都要求 ADMIN（AuthService.requireAdmin——非 ADMIN 403 FORBIDDEN）；
 * 认证由 ApiTokenFilter 横切覆盖（/api/v1/** 全 Bearer，K-46）。
 * 响应键按 D-041 snake_case（user_id），只含 user_id/username/role——不含密码/令牌任何形态。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AuthService authService;
    private final LoginLockoutService loginLockout;

    public AdminUserController(AuthService authService, LoginLockoutService loginLockout) {
        this.authService = authService;
        this.loginLockout = loginLockout;
    }

    public record CreateUserRequest(String username, String password, String role) {
    }

    /** 建号：{username,password,role:"USER"}（role 缺省 USER；ADMIN 不开放——由首注册认领产生）。 */
    @PostMapping("/users")
    @ResponseBody
    public Map<String, Object> create(@RequestBody(required = false) CreateUserRequest body) {
        authService.requireAdmin(UserContext.current());
        AuthService.UserInfo info = authService.createUserAsAdmin(
                body == null ? null : body.username(),
                body == null ? null : body.password(),
                body == null ? null : body.role());
        return Map.of("user_id", info.userId(), "username", info.username(), "role", info.role());
    }

    /** 成员列表：[{user_id,username,role}]——按 id 序，不含 password_hash/token_hash 任何形态。 */
    @GetMapping("/users")
    @ResponseBody
    public List<Map<String, Object>> list() {
        authService.requireAdmin(UserContext.current());
        return authService.listUsers().stream()
                .map(u -> Map.<String, Object>of("user_id", u.userId(), "username", u.username(), "role", u.role()))
                .toList();
    }

    /** T4.81/P-29 后半：管理员解锁（清零 failed_login_attempts / 清空 locked_until）。
     *  幂等：未锁定账号也返回 200 {"user_id":..,"unlocked":true}；用户不存在 → 404 NOT_FOUND；
     *  非 ADMIN → 403 FORBIDDEN（requireAdmin），未认证 → 401（ApiTokenFilter 横切）。 */
    @PostMapping("/users/{id}/unlock")
    @ResponseBody
    public Map<String, Object> unlock(@PathVariable("id") long id) {
        authService.requireAdmin(UserContext.current());
        if (loginLockout.reset(id) == 0) {
            throw new ApiException(404, "NOT_FOUND", "账号不存在或已被删除");
        }
        return Map.of("user_id", id, "unlocked", true);
    }
}
