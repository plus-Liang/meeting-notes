package com.meetingnotes.web;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 账号令牌（T4.37a）：明文 32 字节 SecureRandom → Base64URL（无填充），只在响应出现一次；
 * DB 只存 SHA-256 hex（与 share token 同口径，T4.28）。T4.71 多设备会话：一个用户可并存
 * 多个会话令牌（见 user_session 表），登录/注册各签发一条；登出只失效当前令牌。
 */
@Component
public class TokenService {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** 生成新令牌明文（Base64URL 无填充，43 字符）。 */
    public String generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** SHA-256 hex（64 位小写）——落库口径。 */
    public String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
