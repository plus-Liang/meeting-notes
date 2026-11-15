package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-21 启动预检（T4.12）：显式开启云档但 LLM_* 三键没配齐 → 最早阶段 fail-fast 且文案可读（点名缺失键）；
 * 自动判断（显式留空）与配齐场景不受影响（D-039 显式优先语义不变，NoKeyStartupTest 的空键启动不受影响）。
 */
class LlmStartupPrecheckTest {

    @Test
    void explicitTrueWithAllKeysEmptyFailsFastNamingMissingKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.llm.enabled", "true");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LlmModelSwitchEnvironmentPostProcessor.assertCloudKeysPresent(env));
        String msg = e.getMessage();
        assertTrue(msg.contains("LLM_BASE_URL"), "文案要点名缺失键：" + msg);
        assertTrue(msg.contains("LLM_API_KEY"), "文案要点名缺失键：" + msg);
        assertTrue(msg.contains("LLM_MODEL"), "文案要点名缺失键：" + msg);
        assertTrue(msg.contains("LLM_ENABLED"), "文案要给出修法（改开关）：" + msg);
    }

    @Test
    void explicitTrueWithKeysConfiguredDoesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.llm.enabled", "true");
        env.setProperty("LLM_BASE_URL", "https://api.deepseek.com");
        env.setProperty("LLM_API_KEY", "sk-test");
        env.setProperty("LLM_MODEL", "deepseek-flash");
        assertDoesNotThrow(() -> LlmModelSwitchEnvironmentPostProcessor.assertCloudKeysPresent(env));
    }

    /** D-039：显式留空 = 自动判断路径（key 空 → enabled=false），预检不得触发（NoKeyStartupTest 场景）。 */
    @Test
    void explicitEmptyKeepsAutoDetectAndDoesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.llm.enabled", "");
        assertDoesNotThrow(() -> new LlmModelSwitchEnvironmentPostProcessor()
                .postProcessEnvironment(env, null));
    }
}
