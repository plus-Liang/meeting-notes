package com.meetingnotes.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * T2.7：把 {@code app.log-json}（LOG_JSON）接到 Spring Boot 3.5 内建结构化日志——
 * true（默认）→ {@code logging.structured.format.console=logstash}（无第三方依赖）；
 * false → 不设置该属性 → 回退为人类可读文本。yml 做不了"条件缺席"，所以放 EnvironmentPostProcessor。
 */
public class JsonLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String STRUCTURED_FORMAT_KEY = "logging.structured.format.console";
    private static final String SOURCE_NAME = "jsonLoggingTuning";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String logJson = environment.getProperty("app.log-json",
                environment.getProperty("LOG_JSON", "true"));
        Map<String, Object> props = new HashMap<>();
        if ("true".equalsIgnoreCase(logJson.trim())) {
            props.put(STRUCTURED_FORMAT_KEY, "logstash");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
    }
}
