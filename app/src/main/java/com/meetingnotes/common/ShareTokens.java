package com.meetingnotes.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 分享 token 工具（T4.28）：生成 32 字节 SecureRandom 的 base64url 无填充 token（43 字符）；
 * 落库/查询一律只存/只比 SHA256 hex（64 字符）——**token 明文永不落库**（K-46 纪律）。
 * 生成与哈希分属 task/ShareService 与 web/ShareController 两侧使用，故放 common（宪法 VII 共享契约）。
 */
public final class ShareTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareTokens() {
    }

    /** 32 字节 SecureRandom → base64url 无填充（43 字符，URL 安全可直接拼 query）。 */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA256 hex（64 字符）——DB 存这个，不存明文。 */
    public static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
