package com.meetingnotes;

import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0.4：Java 侧到自建 ASR 服务的最小冒烟测试。
 *
 * <p>只加载转写相关的自动配置，不拉起真实应用上下文（后者依赖 PG / Redis / Flyway，
 * 属于 T1.x 的范围）。所有地址 / 模型名 / 音频路径都来自环境变量，代码里不写死。
 *
 * <p>运行前需要设置（取值见 .env.example，代码里不写死地址或路径）：
 * <pre>
 *   ASR_BASE_URL     本机测试要覆盖为宿主机映射端口对应的地址；容器内由 compose 提供
 *   ASR_SMOKE_AUDIO  本地真实音频的绝对路径
 * </pre>
 */
@SpringBootTest(
        classes = AsrSmokeTest.MinimalContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AsrSmokeTest {

    @Autowired
    private TranscriptionModel transcriptionModel;

    @Value("${ASR_SMOKE_AUDIO:}")
    private String audioPath;

    @Value("${spring.ai.openai.audio.transcription.base-url}")
    private String asrBaseUrl;

    @Test
    void transcribeLocalAudioAndAssertTextIsNotBlank() {
        assertTrue(StringUtils.hasText(audioPath),
                "ASR_SMOKE_AUDIO 未设置：请用环境变量指定本地音频路径（禁止写进代码）");
        Path audio = Path.of(audioPath);
        assertTrue(Files.isRegularFile(audio), "音频文件不存在: " + audioPath);

        String text = transcriptionModel.transcribe(new FileSystemResource(audio));

        assertNotNull(text, "转写返回的 text 为 null");
        assertFalse(text.isBlank(), "转写返回的 text 为空");

        System.out.println("[AsrSmokeTest] base-url = " + asrBaseUrl);
        System.out.println("[AsrSmokeTest] audio    = " + audio.getFileName());
        System.out.println("[AsrSmokeTest] text     = " + text);
    }

    /**
     * 只导入转写链路必需的自动配置（不启用 @EnableAutoConfiguration，避免拉起 PG / Redis / Flyway）。
     * Retry 自动配置提供 RetryTemplate 与 ResponseErrorHandler，是转写 Bean 的必需依赖。
     */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            SpringAiRetryAutoConfiguration.class,
            RestClientAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            OpenAiAudioTranscriptionAutoConfiguration.class
    })
    static class MinimalContext {
    }
}
