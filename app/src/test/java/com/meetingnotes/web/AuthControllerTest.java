package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.70 C2/C3：auth/status 响应形状 + admin 建号/列表端点（权限闸与响应键集，D-041 snake_case）。
 * 只 mock AuthService，UserContext 手工 set/clear（过滤器链不在单测范围，端到端见隔离栈 A3）。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private AuthController authController;
    private AdminUserController adminController;

    @BeforeEach
    void setUp() {
        /* Mockito 在 beforeEach 才注入 @Mock——controller 必须在此之后构造（字段初始化器会拿到 null） */
        authController = new AuthController(authService);
        adminController = new AdminUserController(authService);
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void statusExposesRegistrationFlagAndAdminClaim() {
        when(authService.registrationEnabled()).thenReturn(false);
        when(authService.adminClaimed()).thenReturn(true);

        Map<String, Object> body = authController.status();

        assertEquals(false, body.get("registrationEnabled"));
        assertEquals(true, body.get("adminClaimed"));
        assertTrue(body.keySet().stream().allMatch(k -> k.equals("registrationEnabled") || k.equals("adminClaimed")),
                "不得泄露用户名/哈希等任何其它字段：" + body.keySet());
    }

    @Test
    void adminCreateDelegatesAndReturnsSnakeCaseKeys() {
        UserContext.set(1L);
        when(authService.createUserAsAdmin("carol", "password123", "USER"))
                .thenReturn(new AuthService.UserInfo(7L, "carol", "USER"));

        Map<String, Object> body = adminController.create(
                new AdminUserController.CreateUserRequest("carol", "password123", "USER"));

        assertEquals(7L, body.get("user_id"));
        assertEquals("carol", body.get("username"));
        assertEquals("USER", body.get("role"));
        assertTrue(body.keySet().stream()
                        .noneMatch(k -> k.toLowerCase().contains("password") || k.contains("token")),
                "响应不得含密码/令牌字段：" + body.keySet());
        /* 权限闸必须先于建号被调用（真实 ADMIN/USER 判定逻辑在 AuthServiceTest 用真实现覆盖） */
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(authService);
        inOrder.verify(authService).requireAdmin(1L);
        inOrder.verify(authService).createUserAsAdmin("carol", "password123", "USER");
    }

    /** USER 调管理员建号 → 403 FORBIDDEN（服务端闸，前端隐藏只是第二道）。 */
    @Test
    void adminCreateRequiresAdmin403() {
        UserContext.set(2L);
        org.mockito.Mockito.doThrow(new ApiException(403, "FORBIDDEN", "需要管理员权限"))
                .when(authService).requireAdmin(2L);

        ApiException e = assertThrows(ApiException.class, () -> adminController.create(
                new AdminUserController.CreateUserRequest("dave", "password123", "USER")));
        assertEquals(403, e.getStatus());
        assertEquals("FORBIDDEN", e.getCode());
        verify(authService).requireAdmin(2L);
    }

    @Test
    void adminListReturnsSnakeCaseRowsWithoutSecrets() {
        UserContext.set(1L);
        when(authService.listUsers()).thenReturn(List.of(
                new AuthService.UserInfo(1L, "boss", "ADMIN"),
                new AuthService.UserInfo(7L, "carol", "USER")));

        List<Map<String, Object>> rows = adminController.list();

        assertEquals(2, rows.size());
        assertEquals("carol", rows.get(1).get("username"));
        assertTrue(rows.stream().allMatch(r -> r.keySet().equals(java.util.Set.of("user_id", "username", "role"))),
                "行键集固定为 user_id/username/role（不含密码/令牌）");
        verify(authService).requireAdmin(1L);
    }
}
