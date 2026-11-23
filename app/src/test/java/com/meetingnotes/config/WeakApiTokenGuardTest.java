package com.meetingnotes.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.73：弱口令守卫纯单元（不起 Spring 容器，直接 new——与卡面 C5 一致）。
 * 6 例 = 空白抛 / 默认值抛 / 31 字符抛 / 32 字符不抛 / allow-weak 放行 / 异常消息与日志不含 token 明文。
 */
class WeakApiTokenGuardTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(WeakApiTokenGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private List<String> loggedTexts() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Test
    void blankTokenIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new WeakApiTokenGuard("   ", false).run(null));
        assertTrue(ex.getMessage().contains("未设置"), "实际消息：" + ex.getMessage());
    }

    @Test
    void defaultTokenIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new WeakApiTokenGuard(WeakApiTokenGuard.DEFAULT_TOKEN, false).run(null));
        assertTrue(ex.getMessage().contains("默认值"), "实际消息：" + ex.getMessage());
        assertFalse(ex.getMessage().contains(WeakApiTokenGuard.DEFAULT_TOKEN), "消息不得包含 token 明文");
        assertFalse(loggedTexts().stream().anyMatch(t -> t.contains(WeakApiTokenGuard.DEFAULT_TOKEN)),
                "日志不得包含 token 明文");
    }

    @Test
    void shortTokenIsRejected() {
        String token = "a".repeat(31);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new WeakApiTokenGuard(token, false).run(null));
        assertTrue(ex.getMessage().contains("31"), "实际消息：" + ex.getMessage());
        assertFalse(ex.getMessage().contains(token), "消息不得包含 token 明文");
    }

    @Test
    void strongTokenPasses() {
        assertDoesNotThrow(() -> new WeakApiTokenGuard("x".repeat(32), false).run(null));
    }

    @Test
    void allowWeakBypassesCheck() {
        assertDoesNotThrow(() -> new WeakApiTokenGuard("", true).run(null));
        assertTrue(loggedTexts().stream().anyMatch(t -> t.contains("跳过弱口令校验")),
                "放行路径必须只打 WARN（实际日志：" + loggedTexts() + "）");
    }

    @Test
    void logsNeverContainTokenPlaintext() {
        String token = "t4s73-strong-plaintext-token-0123456789ab";
        assertDoesNotThrow(() -> new WeakApiTokenGuard(token, false).run(null));
        assertFalse(loggedTexts().stream().anyMatch(t -> t.contains(token)), "日志不得包含 token 明文");
        assertThrows(IllegalStateException.class,
                () -> new WeakApiTokenGuard(WeakApiTokenGuard.DEFAULT_TOKEN, false).run(null));
        assertFalse(loggedTexts().stream().anyMatch(t -> t.contains(WeakApiTokenGuard.DEFAULT_TOKEN)),
                "日志不得包含 token 明文");
    }
}
