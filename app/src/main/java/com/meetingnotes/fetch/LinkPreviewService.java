package com.meetingnotes.fetch;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 链接预览（T4.32b POST /api/v1/link/preview）：用户贴一个链接 → 先看"是这个媒体吗"确认卡，
 * 而不是直接建任务或报 400。
 *
 * <p>安全（与 {@link HttpMediaFetcher} 同一套纪律，复用同一道 {@link UrlGuard}，不另写）：
 * 入口与**每个**重定向跳都过闸（400 URL_BLOCKED / 502 FETCH_FAILED）；匿名 GET 不带 cookie/鉴权头；
 * 只读响应体前 app.fetch.preview-max-bytes（默认 2MB），**不整页进内存**（超过即停读，
 * og 标签按拿到的部分解析——title 一般在 head 前 2KB，够用）。
 *
 * <p>分类（卡面逐字）：audio/*、video/*、application/octet-stream → direct；
 * text/html → page（{@link OgMetaParser} 解析 og 元信息）；其它 → unsupported + 可读 reason。
 */
@Service
public class LinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(LinkPreviewService.class);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final UrlGuard guard;
    private final int maxRedirects;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long previewMaxBytes;
    private final boolean byofConfigured;

    @Autowired
    public LinkPreviewService(UrlGuard guard,
                              @Value("${app.fetch.max-redirects}") int maxRedirects,
                              @Value("${app.fetch.connect-timeout-ms}") int connectTimeoutMs,
                              @Value("${app.fetch.read-timeout-ms}") int readTimeoutMs,
                              @Value("${app.fetch.preview-max-bytes}") long previewMaxBytes,
                              @Value("${app.fetch.fetcher-cmd:}") String fetcherCmd) {
        this.guard = guard;
        this.maxRedirects = maxRedirects;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.previewMaxBytes = previewMaxBytes;
        this.byofConfigured = fetcherCmd != null && !fetcherCmd.isBlank();
    }

    public LinkPreviewResponse preview(String url) {
        if (url == null || url.isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "url 不能为空");
        }
        String current = guard.verify(url); // 入口跳同样过闸
        int redirects = 0;
        while (true) {
            HttpURLConnection conn = open(current);
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                conn.disconnect();
                throw fetchFailed(e);
            }
            if (REDIRECT_STATUSES.contains(status)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isBlank()) {
                    throw new ApiException(502, "FETCH_FAILED", "远端重定向缺少 Location 头，无法跟随");
                }
                if (redirects >= maxRedirects) {
                    throw new ApiException(400, "URL_BLOCKED",
                            "重定向次数超过上限 " + maxRedirects + "（疑似重定向环回）");
                }
                URI next;
                try {
                    next = URI.create(current).resolve(location.trim());
                } catch (IllegalArgumentException e) {
                    throw new ApiException(502, "FETCH_FAILED", "远端重定向 Location 无法解析");
                }
                current = guard.verify(next.toString());
                redirects++;
                log.info("预览重定向第 {} 跳：{}", redirects, current);
                continue;
            }
            if (status < 200 || status > 299) {
                conn.disconnect();
                throw new ApiException(502, "FETCH_FAILED", "远端返回 HTTP " + status + "，无法预览该链接");
            }
            String contentType = conn.getContentType();
            String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)
                    .split(";")[0].trim();
            try {
                if (mime.startsWith("text/html")) {
                    String html = readUpTo(conn, previewMaxBytes);
                    OgMetaParser.OgMeta og = OgMetaParser.parse(html);
                    return new LinkPreviewResponse("page", current, contentType,
                            og.title(), resolveImage(current, og.image()), og.durationSec(),
                            byofConfigured, null);
                }
                if (mime.startsWith("audio/") || mime.startsWith("video/")
                        || mime.equals("application/octet-stream")) {
                    return new LinkPreviewResponse("direct", current, contentType,
                            null, null, null, byofConfigured, null);
                }
                return new LinkPreviewResponse("unsupported", current, contentType,
                        null, null, null, byofConfigured,
                        "该链接的 Content-Type 是 " + (mime.isEmpty() ? "未知" : mime)
                                + "，不是音/视频直链也不是网页；请把页面里的媒体导出成文件后上传");
            } catch (IOException e) {
                throw fetchFailed(e);
            } finally {
                conn.disconnect();
            }
        }
    }

    /** 相对路径 og:image 按 final_url 补全为绝对地址；不是合法 URL 就原样返回（不许抛）。 */
    static String resolveImage(String baseUrl, String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        try {
            return URI.create(baseUrl).resolve(image.trim()).toString();
        } catch (IllegalArgumentException e) {
            return image.trim();
        }
    }

    /** 只读前 maxBytes 字节并按 UTF-8 解码（og 标签在 head 开头，截断只影响极长内联资源）。 */
    private static String readUpTo(HttpURLConnection conn, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                int take = (int) Math.min(n, maxBytes - total);
                sb.append(new String(buffer, 0, take, StandardCharsets.UTF_8));
                total += take;
                if (total >= maxBytes) {
                    break; // 只预览元信息，超限即停读，不整页进内存
                }
            }
        }
        return sb.toString();
    }

    /** 匿名 GET：手动跟随（逐跳校验的前提），超时来自配置，不带 cookie/鉴权头。 */
    private HttpURLConnection open(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", "meeting-notes-fetcher/1.0");
            return conn;
        } catch (IOException e) {
            throw fetchFailed(e);
        }
    }

    private static ApiException fetchFailed(IOException e) {
        String reason = e instanceof SocketTimeoutException ? "预览超时" : "预览失败";
        return new ApiException(502, "FETCH_FAILED", reason + "：" + e.getMessage());
    }

    /** 预览响应（对外 snake_case，全局 Jackson 策略）：kind=direct|page|unsupported。 */
    public record LinkPreviewResponse(
            String kind,
            String finalUrl,
            String contentType,
            String title,
            String image,
            Long durationSec,
            boolean byofAvailable,
            String reason) {
    }
}
