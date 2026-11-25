package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.90/S1 契约测试：SnakeYAML 直接解析 classpath 的 application.yml（不启动 Spring 上下文）。
 * 不变量：WORKER_POLL_TIMEOUT_SEC < REDIS_TIMEOUT ≤ 4s < healthcheck timeout 5s；
 * REDIS_CONNECT_TIMEOUT < REDIS_TIMEOUT。三条 value 必须是 ${VAR:default} 占位符（禁硬编码）。
 * 占位符解析模拟 Spring 覆盖顺序：系统属性 > 环境变量 > yml 默认值——
 * 验收 A3 负向：-DWORKER_POLL_TIMEOUT_SEC=5（或同名环境变量）时 poll≥timeout，本测试必须红。
 */
class RedisTimeoutInvariantTest {

    /** ${VAR:default} 占位符形态（C3-4）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*):([^}]*)}$");

    /** compose/Dockerfile 的 healthcheck timeout: 5s——既定预算（本卡不改），阈值留 1s 余量。 */
    private static final long HEALTHCHECK_TIMEOUT_MS = 5000;

    private Map<String, Object> yml() {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath:/application.yml 必须存在");
            return new Yaml().load(in);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取 application.yml 失败: " + e, e);
        }
    }

    @SuppressWarnings("unchecked")
    private String raw(String... path) {
        Object node = yml();
        for (String key : path) {
            assertTrue(node instanceof Map, "yml 节点不是 map，无法取键: " + key);
            node = ((Map<String, Object>) node).get(key);
        }
        assertNotNull(node, "缺少配置键: " + String.join(".", path));
        return String.valueOf(node);
    }

    /** 解析 ${VAR:default}：系统属性 > 环境变量 > 默认值（与 Spring Boot 覆盖顺序一致）。 */
    private String resolve(String raw) {
        Matcher m = PLACEHOLDER.matcher(raw);
        assertTrue(m.matches(), "值必须是 ${VAR:default} 占位符，实际=" + raw);
        String var = m.group(1);
        String override = System.getProperty(var);
        if (override == null) {
            override = System.getenv(var);
        }
        return override != null ? override : m.group(2);
    }

    /** "3s"/"500ms"/"2m" → 毫秒；裸数字按秒（poll-timeout-sec 的口径）。 */
    private long millis(String value) {
        Matcher m = Pattern.compile("^(\\d+)(ms|s|m|h)?$").matcher(value.trim());
        assertTrue(m.matches(), "无法解析时长: " + value);
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2);
        return switch (unit) {
            case "ms" -> n;
            case "s" -> n * 1000;
            case "m" -> n * 60_000;
            default -> n * 3_600_000;
        };
    }

    private long commandTimeoutMs() {
        return millis(resolve(raw("spring", "data", "redis", "timeout")));
    }

    private long pollMs() {
        return millis(resolve(raw("app", "worker", "poll-timeout-sec")));
    }

    private long connectMs() {
        return millis(resolve(raw("spring", "data", "redis", "connect-timeout")));
    }

    @Test
    void commandTimeoutExistsAndAtMost4s() {
        long t = commandTimeoutMs();
        assertTrue(t > 0 && t <= HEALTHCHECK_TIMEOUT_MS - 1000,
                "spring.data.redis.timeout 必须 ≤4s（healthcheck 5s 留 1s 余量），实际=" + t + "ms");
    }

    @Test
    void pollTimeoutStrictlyLessThanCommandTimeout() {
        long p = pollMs();
        long t = commandTimeoutMs();
        assertTrue(p < t, "app.worker.poll-timeout-sec 必须严格小于 spring.data.redis.timeout"
                + "（否则 BLPOP 阻塞取队被命令超时切断、worker 空转），实际 poll=" + p + "ms, timeout=" + t + "ms");
    }

    @Test
    void connectTimeoutExistsAndLessThanCommandTimeout() {
        long c = connectMs();
        long t = commandTimeoutMs();
        assertTrue(c > 0 && c < t,
                "spring.data.redis.connect-timeout 必须存在且 < timeout，实际 connect=" + c + "ms, timeout=" + t + "ms");
    }

    @Test
    void allThreeValuesAreEnvPlaceholders() {
        String[][] keys = {
                {"spring", "data", "redis", "timeout"},
                {"spring", "data", "redis", "connect-timeout"},
                {"app", "worker", "poll-timeout-sec"}
        };
        for (String[] path : keys) {
            String v = raw(path);
            assertTrue(PLACEHOLDER.matcher(v).matches(),
                    String.join(".", path) + " 必须是 ${VAR:default} 占位符（禁硬编码字面量），实际=" + v);
        }
    }
}