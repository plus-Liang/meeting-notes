package com.meetingnotes.webhook;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.fetch.UrlGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;

/**
 * T4.83/P-33：webhook 出站发送器（从 {@link WebhookDeliveryService} 抽出——service 恰 300 行 M3 上限）。
 * 职责：目标地址校验（SSRF 防护）+ 出站 HTTP + HMAC 签名。
 *
 * <p>SSRF 防护口径：校验放在<b>每次真实发送前</b>（{@link #send} 第一行）——不放 onTerminal/scanDue
 * 入队处：入队到真正发送之间最长有 60s 退避 + 多次重试，DNS 可能在这期间被改写（重绑定）。
 * {@code allow-private=false}（默认）时过 {@link UrlGuard#verify}：拒回环 / 私有 / 链路本地 /
 * 云元数据（169.254.169.254）/ 组播 / IPv6 ULA，统一 400 URL_BLOCKED。
 * {@code allow-private=true} 完全跳过——自托管内网接收方（http://nas:9001/hook）的显式出口，
 * <b>那等于允许服务端被配置成内网探测点，风险自担</b>（接入指南 §3.9 同口径）。
 *
 * <p>重定向：{@code followRedirects(NEVER)} 显式写死（默认值即 NEVER）——防将来有人改成 ALWAYS
 * 而绕过校验；<b>若将来允许跟随重定向，每一跳都必须重新 guard.verify</b>，302 到内网即绕过本类全部校验。
 */
@Component
public class WebhookHttpSender {

    private final UrlGuard guard;
    private final boolean allowPrivate;
    private final String secret;
    private final Duration requestTimeout;
    private final HttpClient http;

    public WebhookHttpSender(UrlGuard guard,
                             @Value("${app.webhook.allow-private:false}") boolean allowPrivate,
                             @Value("${app.webhook.secret}") String secret,
                             @Value("${app.webhook.timeout-sec}") long timeoutSec) {
        this.guard = guard;
        this.allowPrivate = allowPrivate;
        this.secret = secret == null ? "" : secret.trim();
        this.requestTimeout = Duration.ofSeconds(Math.max(1, timeoutSec));
        this.http = HttpClient.newBuilder()
                // NEVER 是 JDK 默认值，仍显式写死：防将来改成 ALWAYS 而绕过校验——
                // 若允许跟随重定向，每一跳都必须重新 guard.verify（302 到内网即绕过本类全部校验）
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 目标地址校验：allow-private=false 时过 UrlGuard（回环/私有/链路本地/云元数据/组播/IPv6 ULA → 400 URL_BLOCKED）。
     *  包级可见供单测（零网络，不真发请求）。 */
    void verifyTarget(String url) {
        if (!allowPrivate) {
            guard.verify(url);
        }
    }

    /** 出站发送：第一行先校验目标（每次真实发送前，防 DNS 重绑定/配置漂移）；五个契约头逐字保留（接入指南 §4.2）；
     *  T4.86/P-26：追加 X-Request-Id 关联头（值由调用方解析——任务行 request_id，缺省回退 "task-<taskId>"，判定在 service）。 */
    int send(String url, String event, String deliveryId, String requestId, byte[] body) throws Exception {
        verifyTarget(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-MeetingNotes-Event", event)
                .header("X-MeetingNotes-Delivery", deliveryId)
                .header("X-MeetingNotes-Signature", "sha256=" + sign(secret, body));
        if (requestId != null && !requestId.isBlank()) {
            builder.header("X-Request-Id", requestId); // T4.86/P-26：接收方可据此反查入口请求
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** HMAC-SHA256(secret, 原始请求体字节) → hex 小写（接入方按 §4.3 校验；原样自 WebhookDeliveryService 迁来）。 */
    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
