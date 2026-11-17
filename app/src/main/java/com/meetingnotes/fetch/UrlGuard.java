package com.meetingnotes.fetch;

import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF 防护（T4.32a / spec N7）：链接导入（POST /api/v1/tasks/link）入口 URL 与每个重定向跳
 * 都必须过本校验——非 http/https 拒；host 空 / 携带 userinfo（user:pass@）拒；
 * DNS 解析出的**每个 IP** 都必须不是回环 / 私有 / 链路本地 / 组播 / 未指定 / IPv6 ULA（fc00::/7）/
 * IPv4 映射（显式覆盖 169.254.169.254 云元数据）。拒绝统一 400 URL_BLOCKED（卡面逐字）。
 *
 * <p>判 IP 一律**按解析结果**：十进制/八进制/十六进制缩写（2130706433、0177.0.0.1、0x7f.0.0.1、127.1）
 * 由解析器归一化成标准地址后判定（系统解析器 InetAddress.getAllByName 对数字形式本地解析、不查 DNS）。
 * 解析器接口可注入——单测用 stub，不依赖真 DNS（卡判据）；解析失败（域名不存在）按网络失败
 * 502 FETCH_FAILED 处理，不算安全违规。
 *
 * <p>allow-hosts（app.fetch.allow-hosts，默认空）：命中白名单的主机跳过 DNS 与 IP 拦截——**仅限自测 /
 * 受信内网部署**，生产留空；风险口径写接入指南 §3.9。
 */
@Component
public class UrlGuard {

    /** DNS 解析抽象：生产用系统解析器，单测注入 stub（不依赖真 DNS）。 */
    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws IOException;
    }

    /**
     * 系统解析器。十进制/八进制/十六进制缩写 IP 的规范化在 {@link #parseNumericLiteralHost(String)}
     * 内完成（见其说明——不能交给系统解析器），本解析器只负责真正的域名查询。
     */
    public static final HostResolver SYSTEM_RESOLVER = host -> List.of(InetAddress.getAllByName(host));

    private final HostResolver resolver;
    private final Set<String> allowHosts;

    @Autowired
    public UrlGuard(@Value("${app.fetch.allow-hosts:}") String allowHosts) {
        this(SYSTEM_RESOLVER, parseAllowHosts(allowHosts));
    }

    public UrlGuard(HostResolver resolver, Collection<String> allowHosts) {
        this.resolver = resolver;
        this.allowHosts = Set.copyOf(parseAllowHosts(String.join(",", allowHosts)));
    }

    /** 逗号分隔主机白名单 → 归一化（小写、去尾点、去空白）；空串 → 空列表。 */
    public static List<String> parseAllowHosts(String raw) {
        List<String> hosts = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return hosts;
        }
        for (String part : raw.split(",")) {
            String host = part.trim().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    /**
     * 校验一个 URL（入口与每个重定向跳复用同一道闸）。
     *
     * @return 通过校验的 URL 原文（不做重写；请求方按原文构造请求）
     * @throws ApiException 400 URL_BLOCKED（格式/协议/userinfo/内网 IP）或 502 FETCH_FAILED（域名解析失败）
     */
    public String verify(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw blocked("URL 无法解析");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw blocked("只允许 http/https 媒体直链（收到 scheme：" + (scheme.isEmpty() ? "无" : scheme) + "）");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked("URL 缺少合法 host");
        }
        if (uri.getUserInfo() != null) {
            throw blocked("URL 不允许携带 userinfo（user:pass@host 形式）");
        }
        if (allowHosts.contains(normalizeHost(host))) {
            // 白名单命中：跳过 DNS 与 IP 拦截（仅自测/受信内网部署；默认空，风险见接入指南 §3.9）
            return rawUrl;
        }
        // 纯数字 IPv4 字面量：连接目标就是该 IP 本身，无需 DNS——按 inet_aton 语义规范化后判定
        InetAddress literal = parseNumericLiteralHost(host);
        if (literal != null) {
            if (isBlockedAddress(literal)) {
                throw blocked("目标地址指向内网/回环/链路本地等被保护网段（" + literal.getHostAddress() + "）");
            }
            return rawUrl;
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new ApiException(502, "FETCH_FAILED", "域名解析失败：" + host);
        } catch (IOException e) {
            throw new ApiException(502, "FETCH_FAILED", "域名解析异常：" + host);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new ApiException(502, "FETCH_FAILED", "域名解析失败：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw blocked("目标地址指向内网/回环/链路本地等被保护网段（" + address.getHostAddress() + "）");
            }
        }
        return rawUrl;
    }

    /** host 归一化（小写 + 去尾点），仅用于白名单匹配；DNS 查询仍用原 host。 */
    static String normalizeHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /**
     * 纯数字 IPv4 字面量按 inet_aton 语义规范化为标准地址；不是纯数字字面量返回 null（走 DNS）。
     * 支持：单数字（2130706433）、0x 十六进制（0x7f.0.0.1）、前导 0 八进制（0177.0.0.1）、
     * 缺段缩写（127.1 → 回环地址）。
     *
     * <p>为什么不让系统解析器代劳：新版 JDK 出于安全把前导 0 按**十进制**解析（0177 → 177），
     * 与多数客户端的八进制解释不一致——卡面明确这些形式必须拦，故在本类内规范化。
     * 这仍是"按解析结果判"（算出它真正会连到的地址再判），不做字符串黑名单。
     */
    static InetAddress parseNumericLiteralHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return null;
        }
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long value = parseLiteralPart(parts[i]);
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        byte[] bytes;
        if (parts.length == 1) {
            bytes = packNumber(values[0], 4);
        } else {
            // inet_aton：最后一段按剩余字节数解释（127.1 → 127 + 24 位 1 = 回环地址）
            int remaining = 4 - parts.length + 1;
            byte[] head = new byte[parts.length - 1];
            for (int i = 0; i < parts.length - 1; i++) {
                if (values[i] > 255) {
                    return null;
                }
                head[i] = (byte) values[i];
            }
            byte[] tail = packNumber(values[parts.length - 1], remaining);
            if (tail == null) {
                return null;
            }
            byte[] all = new byte[4];
            System.arraycopy(head, 0, all, 0, head.length);
            System.arraycopy(tail, 0, all, head.length, tail.length);
            bytes = all;
        }
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null; // getByAddress(4 字节) 不会抛，防御性兜底
        }
    }

    /** 单段：0x 十六进制 / 前导 0 八进制 / 十进制；非法字符或溢出 → null。 */
    private static Long parseLiteralPart(String part) {
        if (part.isEmpty()) {
            return null;
        }
        try {
            if (part.length() > 2 && (part.startsWith("0x") || part.startsWith("0X"))
                    && part.substring(2).matches("[0-9a-fA-F]+")) {
                if (part.length() - 2 > 8) {
                    return null;
                }
                return Long.parseLong(part.substring(2), 16);
            }
            if (part.matches("\\d+")) {
                if (part.length() > 1 && part.charAt(0) == '0') {
                    if (part.length() - 1 > 12) {
                        return null;
                    }
                    return Long.parseLong(part.substring(1), 8);
                }
                if (part.length() > 10) {
                    return null;
                }
                return Long.parseLong(part, 10);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** 把 ≤ 8*bytes 位的数值打成大端字节数组；溢出 → null。 */
    private static byte[] packNumber(long value, int bytes) {
        if (value < 0 || value >= (1L << (8L * bytes))) {
            return null;
        }
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (value >> (8L * (bytes - 1 - i)));
        }
        return out;
    }

    /**
     * 单个解析结果是否属于被保护网段。IPv4 直接按字节判；IPv6 先解 IPv4 映射
     * （::ffff:a.b.c.d，Java 可能返回 Inet6Address，其内建判断对映射地址不可靠），再按 IPv6 判。
     */
    static boolean isBlockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (isIpv4Mapped(bytes)) {
            byte[] v4 = new byte[4];
            System.arraycopy(bytes, 12, v4, 0, 4);
            return isBlockedIpv4(v4);
        }
        return address.isLoopbackAddress()          // ::1
                || address.isAnyLocalAddress()      // ::（未指定）
                || address.isLinkLocalAddress()     // fe80::/10
                || address.isMulticastAddress()     // ff00::/8
                || address.isSiteLocalAddress()     // fec0::/10（已废弃站点本地，一并拒）
                || (bytes[0] & 0xFE) == 0xFC;       // fc00::/7 ULA
    }

    private static boolean isBlockedIpv4(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 0                                          // 0.0.0.0/8（未指定/本网络）
                || first == 10                                     // 10/8 私有
                || first == 127                                    // 127/8 回环
                || (first == 169 && second == 254)                 // 169.254/16 链路本地（含云元数据）
                || (first == 172 && second >= 16 && second <= 31)  // 172.16/12 私有
                || (first == 192 && second == 168)                 // 192.168/16 私有
                || first >= 224;                                   // 224/4 组播 + 240/4 保留（含广播）
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static ApiException blocked(String message) {
        return new ApiException(400, "URL_BLOCKED", message);
    }
}
