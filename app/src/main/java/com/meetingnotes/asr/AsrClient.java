package com.meetingnotes.asr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

/**
 * ASR HTTP 客户端（T4.35a）：**一次**调用同时拿 text + duration + segments——
 * Spring AI 的 {@code TranscriptionModel.transcribe(Resource)} 只回字符串拿不到句子级时间戳，
 * 卡面明确"不许为了拿时间戳再调一次 ASR"，故换成本仓库内轻量客户端，TranscriptionWorker 改用它。
 *
 * <p>复用既有纪律（不另起炉灶）：
 * <ul>
 *   <li>base-url：{@code spring.ai.openai.audio.transcription.base-url}（http://asr:8000，
 *       不带 /v1——路径常量 {@link AsrHttpClientConfig} 的 /v1/audio/transcriptions 由本类拼，
 *       D-034：base-url 带 /v1 会 404）；</li>
 *   <li>超时：走 {@code RestClient.Builder}（AsrHttpClientConfig 的 RestClientCustomizer 按 URI
 *       路由——本类只打 /v1/audio/transcriptions → 读超时 app.asr.timeout-sec 900s、连接 5s）；</li>
 *   <li>multipart 字段与 Spring AI 既有请求一致：file + model（response-format=json 是 ASR 服务
 *       固定契约，服务端只回 JSON）；</li>
 *   <li>失败语义不变：网络/HTTP 异常以 RuntimeException 抛出，由 worker 包成
 *       ProcessingException("ASR_ERROR") 走既有重试/死信路径。</li>
 * </ul>
 */
@Component
public class AsrClient {

    /** 契约端点路径（与 config/AsrHttpClientConfig 的路由常量同一字面量：转写路径走 900s 读超时）。 */
    static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";

    /** 一次调用的完整结果：text（拼接全文）+ segments（句子级时间戳，可能为 null=响应无该键）
     *  + language（T4.99：ASR 响应带回的实际语言，响应无该键时为 null，只增不减）。 */
    public record Transcription(String text, java.util.List<AsrResponseParser.Segment> segments, String language) {
    }

    private final RestClient restClient;
    private final String model;

    public AsrClient(RestClient.Builder builder,
                     @Value("${spring.ai.openai.audio.transcription.base-url}") String baseUrl,
                     @Value("${spring.ai.openai.audio.transcription.options.model}") String model) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.model = model;
    }

    /** 转写一个 16k 单声道 wav（无任务级语言 → 部署级默认），返回 text + segments。 */
    public Transcription transcribe(Path wav) {
        return transcribe(wav, null);
    }

    /** T4.99/C3：任务级语言重载——null/空白**不加** language 字段（服务端用部署级默认）；
     *  "auto" 原样传（服务端解释为自动检测）；其它合法码原样传（校验在入口层已做）。 */
    public Transcription transcribe(Path wav, String language) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(wav));
        body.add("model", model);
        if (language != null && !language.isBlank()) {
            body.add("language", language);
        }
        String raw = restClient.post()
                .uri(TRANSCRIPTION_PATH)
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
        AsrResponseParser.AsrResult parsed = AsrResponseParser.parse(raw);
        if (parsed.text() == null) {
            throw new IllegalStateException("ASR 响应不可解析（非 JSON 或缺 text 键）：" + abbreviate(raw));
        }
        return new Transcription(parsed.text(), parsed.segments(), parsed.language());
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
