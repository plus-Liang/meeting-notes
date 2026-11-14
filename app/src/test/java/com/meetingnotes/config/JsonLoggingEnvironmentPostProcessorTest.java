package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T2.7：LOG_JSON 开关 → 结构化日志格式的机器判据（true=logstash，false=属性缺席=可读文本）。 */
class JsonLoggingEnvironmentPostProcessorTest {

    private final JsonLoggingEnvironmentPostProcessor processor = new JsonLoggingEnvironmentPostProcessor();

    private MockEnvironment apply(MockEnvironment env) {
        processor.postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void defaultEnablesLogstashConsoleFormat() {
        MockEnvironment env = apply(new MockEnvironment());
        assertEquals("logstash", env.getProperty(JsonLoggingEnvironmentPostProcessor.STRUCTURED_FORMAT_KEY),
                "缺省必须开结构化日志（app.log-json 默认 true）");
    }

    @Test
    void explicitTrueEnablesLogstashConsoleFormat() {
        MockEnvironment env = apply(new MockEnvironment().withProperty("app.log-json", "true"));
        assertEquals("logstash", env.getProperty(JsonLoggingEnvironmentPostProcessor.STRUCTURED_FORMAT_KEY));
    }

    @Test
    void falseKeepsHumanReadableText() {
        MockEnvironment env = apply(new MockEnvironment().withProperty("app.log-json", "false"));
        assertNull(env.getProperty(JsonLoggingEnvironmentPostProcessor.STRUCTURED_FORMAT_KEY),
                "LOG_JSON=false 时属性必须缺席（Spring Boot 无该属性即回退可读文本）");
    }
}
