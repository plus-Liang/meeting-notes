package com.meetingnotes.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 本地 Ollama 探活（T3.3 ①）：{@code GET {OLLAMA_BASE_URL}/models}，**独立 2s 超时**。
 *
 * <p>禁止复用 chat 的 120s 读超时（否则一次探活就能把 worker 拖住两分钟，同 D-059 的理由）；
 * 探活失败**只 WARN**，绝不让应用起不来（K-04 铁律：尽力而为）。
 */
@Component
public class OllamaProbe {

    private static final Logger log = LoggerFactory.getLogger(OllamaProbe.class);

    /** D-059 同口径：探活超时固定 2s，独立于业务超时。 */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final String modelsUrl;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public OllamaProbe(@Value("${app.llm.ollama.base-url}") String baseUrl) {
        this(modelsUrlOf(baseUrl), HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build());
    }

    /** 供单测注入（无网络）。 */
    OllamaProbe(String modelsUrl, HttpClient httpClient) {
        this.modelsUrl = modelsUrl;
        this.httpClient = httpClient;
    }

    static String modelsUrlOf(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/models";
    }

    /** @return true = 本地 LLM 可达（HTTP 2xx） */
    public boolean reachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(modelsUrl))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                log.info("本地 LLM 探活成功（{}，HTTP {}）：第二档可用", modelsUrl, status);
                return true;
            }
            log.warn("本地 LLM 探活返回 HTTP {}（{}）：本地档不可用，本题降为抽取式兜底；应用照常启动",
                    status, modelsUrl);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("本地 LLM 探活被中断（{}）：本地档不可用，应用照常启动", modelsUrl);
            return false;
        } catch (Exception e) {
            log.warn("本地 LLM 探活失败（{}）：{}——本地档不可用，本题降为抽取式兜底；应用照常启动",
                    modelsUrl, e.toString());
            return false;
        }
    }
}
