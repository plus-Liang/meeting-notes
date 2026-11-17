package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.28：分享创建/撤销——token 只存 SHA256（明文不可查）、TTL 可配、404/400 口径、撤销计数。 */
@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private ShareService newService(int ttlDays) {
        return new ShareService(jdbc, ttlDays);
    }

    @Test
    void createStoresOnlyHashAndHonorsTtl() {
        when(jdbc.query(contains("SELECT status FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
                eq(7L), eq(1L))).thenReturn("DONE");

        ShareService.ShareCreated created = newService(7).create(1L, 7L);

        // token 明文形状：base64url 无填充 43 字符，无 +/
        assertTrue(created.token().matches("[A-Za-z0-9_-]{43}"), "32B base64url = 43 字符：" + created.token());
        assertTrue(created.url().startsWith("/share.html?token="));
        assertEquals(7L, created.taskId());

        // 落库参数：token_hash = SHA256 hex（64 位）且 ≠ 明文；expires_at ≈ now + 7 天
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<OffsetDateTime> expires = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(jdbc).update(contains("INSERT INTO share_link"), eq(7L), eq(1L),
                hash.capture(), expires.capture());
        assertTrue(hash.getValue().matches("[0-9a-f]{64}"), "DB 只存 SHA256 hex");
        assertEquals(com.meetingnotes.common.ShareTokens.sha256Hex(created.token()), hash.getValue(),
                "hash 必须等于 token 的 SHA256（DB 里查不到明文）");
        assertNotEquals(created.token(), hash.getValue());
        long days = java.time.temporal.ChronoUnit.DAYS.between(OffsetDateTime.now(), expires.getValue());
        assertTrue(days >= 6 && days <= 8, "TTL=7 天（±1 天时钟余量），实际 " + days);
    }

    @Test
    void ttlConfigurableToTwoDays() {
        when(jdbc.query(contains("SELECT status FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
                eq(7L), eq(1L))).thenReturn("DONE");

        ShareService.ShareCreated created = newService(2).create(1L, 7L);
        long days = java.time.temporal.ChronoUnit.DAYS.between(OffsetDateTime.now(), created.expiresAt());
        assertTrue(days >= 1 && days <= 3, "SHARE_TTL_DAYS=2 → 过期时间约 2 天后，实际 " + days);
    }

    @Test
    void createRejectsNonDoneWith400() {
        when(jdbc.query(contains("SELECT status FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
                eq(8L), eq(1L))).thenReturn("RUNNING");

        ApiException e = assertThrows(ApiException.class, () -> newService(7).create(1L, 8L));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }

    @Test
    void createMissingTaskReturns404() {
        when(jdbc.query(contains("SELECT status FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
                eq(99L), eq(1L))).thenReturn(null);

        ApiException e = assertThrows(ApiException.class, () -> newService(7).create(1L, 99L));
        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
    }

    @Test
    void revokeAllReturnsAffectedRowsAnd404ForMissing() {
        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(7L), eq(1L))).thenReturn(1);
        when(jdbc.update(contains("revoked_at = now()"), eq(7L))).thenReturn(3);

        assertEquals(3, newService(7).revokeAll(1L, 7L), "撤销条数 = UPDATE 受影响行数");

        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(99L), eq(1L))).thenReturn(null);
        ApiException e = assertThrows(ApiException.class, () -> newService(7).revokeAll(1L, 99L));
        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
    }

    @Test
    void existsActiveByHashFalseForRevokedOrExpired() {
        // 伪造/撤销/过期统一走"查不到有效行"→ false（web 层转 404 SHARE_NOT_FOUND，防探测）
        when(jdbc.query(contains("revoked_at IS NULL AND expires_at > now()"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                any(Object[].class))).thenReturn(List.of());
        assertTrue(!newService(7).existsActiveByHash("deadbeef"), "无有效行 → false");
    }
}
