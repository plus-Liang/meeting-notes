package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.storage.StorageGuard;
import com.meetingnotes.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * T4.92：AdminStorageController——JSON 键集逐字（snake_case，D-041）与权限闸（非 ADMIN 403）。
 * mock StorageService/StorageGuard/AuthService，不起 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class AdminStorageControllerTest {

    @Mock
    private StorageService storageService;
    @Mock
    private StorageGuard storageGuard;
    @Mock
    private AuthService authService;

    @BeforeEach
    void setUpUser() {
        UserContext.set(1L); // ADMIN（requireAdmin 通过）
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private AdminStorageController controller() {
        return new AdminStorageController(storageService, storageGuard, authService);
    }

    /** GET 总览：四段键名逐字（disk/audio/orphans/thresholds），audio 含 truncated/available。 */
    @Test
    void overviewMatchesContractKeySet() {
        when(storageService.usageBundle()).thenReturn(new StorageService.UsageBundle(
                new StorageService.Disk(1000, 250, 75),
                new StorageService.Audio(3, 350, true, true, null),
                new StorageService.Orphans(1, 300, java.util.List.of("tasks/2/o.wav")),
                new StorageService.Trash(1, 120)));
        when(storageGuard.minFreeMb()).thenReturn(2048L);
        when(storageGuard.headroomMb()).thenReturn(256L);

        Map<String, Object> body = controller().overview();

        assertEquals(Set.of("disk", "audio", "orphans", "trash", "thresholds"), body.keySet());
        assertEquals(Set.of("total_bytes", "free_bytes", "used_pct"),
                ((Map<?, ?>) body.get("disk")).keySet());
        assertEquals(Set.of("objects", "bytes", "truncated", "available"),
                ((Map<?, ?>) body.get("audio")).keySet());
        assertEquals(Set.of("objects", "bytes"), ((Map<?, ?>) body.get("orphans")).keySet());
        assertEquals(Set.of("objects", "bytes"), ((Map<?, ?>) body.get("trash")).keySet(), "T4.96/C8：trash 只增段");
        assertEquals(1L, ((Map<?, ?>) body.get("trash")).get("objects"));
        assertEquals(Set.of("min_free_mb", "headroom_mb"), ((Map<?, ?>) body.get("thresholds")).keySet());
        assertEquals(2048L, ((Map<?, ?>) body.get("thresholds")).get("min_free_mb"));
        assertEquals(256L, ((Map<?, ?>) body.get("thresholds")).get("headroom_mb"));
    }

    /** 非 ADMIN → 403 FORBIDDEN（requireAdmin 抛，controller 不吞）。 */
    @Test
    void nonAdminGets403() {
        UserContext.set(2L); // 普通用户
        doThrow(new ApiException(403, "FORBIDDEN", "需要管理员权限"))
                .when(authService).requireAdmin(anyLong());
        ApiException e = assertThrows(ApiException.class, () -> controller().overview());
        assertEquals(403, e.getStatus());
        assertEquals("FORBIDDEN", e.getCode());
    }

    /** cleanup：dryRun 由请求参数透传，响应键集逐字（dry_run/deleted_objects/deleted_bytes/skipped/sample）。 */
    @Test
    void cleanupPassthroughKeys() {
        when(storageService.cleanup(true)).thenReturn(new StorageService.Cleanup(
                true, 2, 350, 1, java.util.List.of("tasks/2/orphan.wav")));
        Map<String, Object> body = controller().cleanup(true);
        assertEquals(Set.of("dry_run", "deleted_objects", "deleted_bytes", "skipped", "sample"), body.keySet());
        assertEquals(true, body.get("dry_run"));
        assertEquals(2L, body.get("deleted_objects"));
        assertEquals(java.util.List.of("tasks/2/orphan.wav"), body.get("sample"));
    }
}
