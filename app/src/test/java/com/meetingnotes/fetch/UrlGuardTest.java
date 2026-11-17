package com.meetingnotes.fetch;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.32a：UrlGuard 单测矩阵（卡判据）——stub 解析器不依赖真 DNS；数字缩写 IP 用系统解析器
 * 本地归一化（不发起网络查询），验证"按解析结果判"。
 */
class UrlGuardTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** stub 解析器：按 host 查表返回预置 IP；查不到即 UnknownHostException。 */
    private static UrlGuard guard(Map<String, List<InetAddress>> table, List<String> allowHosts) {
        return new UrlGuard(host -> {
            List<InetAddress> addresses = table.get(host);
            if (addresses == null) {
                throw new UnknownHostException(host);
            }
            return addresses;
        }, allowHosts);
    }

    private static void assertBlocked(String url, UrlGuard guard) {
        ApiException e = assertThrows(ApiException.class, () -> guard.verify(url));
        assertEquals(400, e.getStatus(), url);
        assertEquals("URL_BLOCKED", e.getCode(), url);
    }

    @Test
    void nonHttpSchemesRejected() {
        UrlGuard guard = guard(Map.of(), List.of());
        assertBlocked("file:///etc/passwd", guard);
        assertBlocked("gopher://example.com/x", guard);
        assertBlocked("ftp://example.com/file", guard);
        assertBlocked("/just/a/path", guard);
    }

    @Test
    void userinfoAndMissingHostRejected() {
        UrlGuard guard = guard(Map.of("example.com", List.of(ip("93.184.216.34"))), List.of());
        assertBlocked("http://user:pass@example.com/x", guard);
        assertBlocked("http:///path", guard);
        assertBlocked("http://", guard);
    }

    @Test
    void protectedAddressMatrixRejected() {
        Map<String, List<InetAddress>> table = Map.ofEntries(
                Map.entry("loop", List.of(ip("127.0.0.1"))),
                Map.entry("[::1]", List.of(ip("::1"))),
                Map.entry("ten", List.of(ip("10.1.2.3"))),
                Map.entry("c172a", List.of(ip("172.16.0.1"))),
                Map.entry("c172b", List.of(ip("172.31.255.255"))),
                Map.entry("c192", List.of(ip("192.168.1.1"))),
                Map.entry("metadata", List.of(ip("169.254.169.254"))),
                Map.entry("zero", List.of(ip("0.0.0.0"))),
                Map.entry("[fc00::1]", List.of(ip("fc00::1"))),
                Map.entry("[fe80::1]", List.of(ip("fe80::1"))),
                Map.entry("[fd00::5]", List.of(ip("fd00::5"))),
                // 多 IP 里只要有一个内网即整体拒绝
                Map.entry("multi", List.of(ip("93.184.216.34"), ip("10.0.0.9"))),
                Map.entry("mapped", List.of(ip("::ffff:169.254.169.254"))));
        UrlGuard guard = guard(table, List.of());
        assertBlocked("http://loop/x", guard);
        assertBlocked("http://[::1]/x", guard);
        assertBlocked("http://ten/x", guard);
        assertBlocked("http://c172a/x", guard);
        assertBlocked("http://c172b/x", guard);
        assertBlocked("http://c192/x", guard);
        assertBlocked("http://metadata/latest/meta-data/", guard);
        assertBlocked("http://zero/x", guard);
        assertBlocked("http://[fc00::1]/x", guard);
        assertBlocked("http://[fe80::1]/x", guard);
        assertBlocked("http://[fd00::5]/x", guard);
        assertBlocked("http://multi/x", guard);
        assertBlocked("http://mapped/x", guard);
    }

    /** 数字缩写（十进制/八进制/十六进制/缺段）由解析器归一化后判——系统解析器对数字形式不查 DNS。 */
    @Test
    void abbreviatedNumericIpsJudgedByResolution() {
        UrlGuard guard = new UrlGuard(UrlGuard.SYSTEM_RESOLVER, List.of());
        assertBlocked("http://2130706433/", guard);
        assertBlocked("http://0177.0.0.1/", guard);
        assertBlocked("http://0x7f.0.0.1/", guard);
        assertBlocked("http://127.1/", guard);
    }

    @Test
    void publicAddressPassesAndHttpsAllowed() {
        UrlGuard guard = guard(Map.of("example.com", List.of(ip("93.184.216.34"))), List.of());
        assertEquals("http://example.com/x", guard.verify("http://example.com/x"));
        assertEquals("https://example.com/x", guard.verify("https://example.com/x"));
    }

    @Test
    void allowHostsBypassesIpChecksButNotSchemeOrUserinfo() {
        UrlGuard guard = guard(Map.of(), List.of("Minio."));
        assertEquals("http://minio/x", guard.verify("http://minio/x"));
        assertEquals("http://minio:9000/x", guard.verify("http://minio:9000/x"));
        assertBlocked("ftp://minio/x", guard);
        assertBlocked("http://u:p@minio/x", guard);
    }

    @Test
    void unresolvedHostIsFetchFailedNotBlocked() {
        UrlGuard guard = guard(Map.of(), List.of());
        ApiException e = assertThrows(ApiException.class, () -> guard.verify("http://nope.invalid/x"));
        assertEquals(502, e.getStatus());
        assertEquals("FETCH_FAILED", e.getCode());
    }
}
