package com.meetingnotes.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.37a：PBKDF2 密码哈希——格式、正确/错误、不同盐、坏格式一律 false（卡判据）。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashFollowsDocumentedFormat() {
        String stored = hasher.hash("s3cret-pass");
        assertTrue(stored.startsWith("pbkdf2-sha256$120000$"), "格式前缀+迭代次数：" + stored);
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length, "pbkdf2-sha256 / 120000 / 盐 / 哈希");
        assertFalse(parts[2].isEmpty());
        assertFalse(parts[3].isEmpty());
        assertFalse(stored.contains("s3cret-pass"), "明文不出现");
    }

    @Test
    void correctPasswordVerifies() {
        String stored = hasher.hash("s3cret-pass");
        assertTrue(hasher.verify("s3cret-pass", stored));
    }

    @Test
    void wrongPasswordFails() {
        String stored = hasher.hash("s3cret-pass");
        assertFalse(hasher.verify("wrong-pass", stored));
        assertFalse(hasher.verify("s3cret-pas", stored));
        assertFalse(hasher.verify("", stored));
    }

    @Test
    void differentSaltsPerHash() {
        String a = hasher.hash("same-password");
        String b = hasher.hash("same-password");
        assertNotEquals(a, b, "同密码两次哈希必须不同盐");
        assertTrue(hasher.verify("same-password", a));
        assertTrue(hasher.verify("same-password", b));
    }

    @Test
    void badStoredFormatsAreAlwaysFalse() {
        String stored = hasher.hash("s3cret-pass");
        assertFalse(hasher.verify("s3cret-pass", null));
        assertFalse(hasher.verify("s3cret-pass", ""));
        assertFalse(hasher.verify("s3cret-pass", "plain-text"));
        assertFalse(hasher.verify("s3cret-pass", "pbkdf2-sha256$"), "缺盐缺哈希");
        assertFalse(hasher.verify("s3cret-pass", "pbkdf2-sha256$120000$onlysalt"), "缺哈希段");
        assertFalse(hasher.verify("s3cret-pass", "pbkdf2-sha256$999$" + stored.substring(stored.indexOf('$', 20) + 1)),
                "未知迭代次数 → 拒绝");
        assertFalse(hasher.verify("s3cret-pass", stored.replace("$", "%")), "分隔符破坏 → 拒绝");
        assertFalse(hasher.verify("s3cret-pass", stored.substring(0, stored.length() - 4) + "AAAA"),
                "哈希段被篡改 → 拒绝");
        assertFalse(hasher.verify("s3cret-pass", stored + "x"), "尾部拼接 → 拒绝");
    }
}
