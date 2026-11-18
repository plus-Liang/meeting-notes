package com.meetingnotes.web;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希（T4.37a）：JDK PBKDF2，**不新增依赖**（卡面口径）。
 *
 * <p>算法 PBKDF2WithHmacSHA256，16 字节 SecureRandom 盐、120000 次迭代、256 位输出；
 * 存储格式 {@code pbkdf2-sha256$120000$<base64盐>$<base64哈希>}。
 * 校验解析失败一律 false；比较用 MessageDigest.isEqual（常量时间）。
 */
@Component
public class PasswordHasher {

    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int ITERATIONS = 120_000;
    static final int SALT_BYTES = 16;
    static final int KEY_BITS = 256;
    static final String PREFIX = "pbkdf2-sha256$" + ITERATIONS + "$";

    private final SecureRandom random = new SecureRandom();

    /** 哈希明文密码（去首尾空白由调用方完成）。 */
    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, ITERATIONS);
        return PREFIX + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derived);
    }

    /** 校验：存储串为空/格式坏/参数缺 → 一律 false（不抛）。 */
    public boolean verify(String password, String stored) {
        if (password == null || stored == null || !stored.startsWith(PREFIX)) {
            return false;
        }
        String[] parts = stored.substring(PREFIX.length()).split("\\$");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            int iterations = parseIterations(stored);
            byte[] derived = pbkdf2(password, salt, iterations);
            return MessageDigest.isEqual(expected, derived);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 头部固定 120000（本卡不引入迭代参数化），存储串里带的数字必须等于它才可信。 */
    private static int parseIterations(String stored) {
        String head = stored.substring("pbkdf2-sha256$".length());
        String count = head.substring(0, head.indexOf('$'));
        int iterations = Integer.parseInt(count);
        if (iterations != ITERATIONS) {
            throw new IllegalArgumentException("未知迭代次数");
        }
        return iterations;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }
}
