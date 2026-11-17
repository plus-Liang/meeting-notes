package com.meetingnotes.fetch;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 默认直链抓取器（T4.32a）：只发匿名 GET（不带 cookie/鉴权头，卡禁止）；
 * **手动**跟随重定向并对每一跳重跑 {@link UrlGuard}（防 302 跳内网），上限 app.fetch.max-redirects（默认 5）；
 * 连接/读超时 app.fetch.connect-timeout-ms（5000）/ read-timeout-ms（10000）；
 * 响应体流式写临时文件，超 app.fetch.max-bytes（200MB）→ 413 SIZE_EXCEEDED；
 * 网络失败/超时/远端非 2xx → 502 FETCH_FAILED；网页（text/html）→ 400 NOT_DIRECT_MEDIA。
 */
@Component
public class HttpMediaFetcher implements MediaFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpMediaFetcher.class);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final UrlGuard guard;
    private final int maxRedirects;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long maxBytes;

    @Autowired
    public HttpMediaFetcher(UrlGuard guard,
                            @Value("${app.fetch.max-redirects}") int maxRedirects,
                            @Value("${app.fetch.connect-timeout-ms}") int connectTimeoutMs,
                            @Value("${app.fetch.read-timeout-ms}") int readTimeoutMs,
                            @Value("${app.fetch.max-bytes}") long maxBytes) {
        this.guard = guard;
        this.maxRedirects = maxRedirects;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxBytes = maxBytes;
    }

    @Override
    public FetchResult fetch(String url, Path destination) {
        String current = guard.verify(url); // 入口跳同样校验（卡：每跳都校验）
        int redirects = 0;
        while (true) {
            HttpURLConnection conn = open(current);
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                conn.disconnect();
                deleteQuietly(destination);
                throw fetchFailed(e);
            }
            if (REDIRECT_STATUSES.contains(status)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isBlank()) {
                    throw new ApiException(502, "FETCH_FAILED", "远端重定向缺少 Location 头，无法跟随");
                }
                if (redirects >= maxRedirects) {
                    // 超上限按安全违规 400（卡判据：>5 跳→400 URL_BLOCKED）；每跳都校验过，环回/跳内网走不到这里
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
                log.info("直链重定向第 {} 跳：{}", redirects, current);
                continue;
            }
            if (status < 200 || status > 299) {
                conn.disconnect();
                throw new ApiException(502, "FETCH_FAILED", "远端返回 HTTP " + status + "，无法下载该直链");
            }
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                conn.disconnect();
                throw new ApiException(400, "NOT_DIRECT_MEDIA",
                        "该链接是网页而非可直接下载的音/视频文件：请把页面里的媒体导出成文件后上传");
            }
            try {
                long bytes = streamToFile(conn, destination);
                return new FetchResult(contentType, bytes);
            } catch (ApiException e) {
                deleteQuietly(destination);
                throw e;
            } catch (IOException e) {
                deleteQuietly(destination);
                throw fetchFailed(e);
            } finally {
                conn.disconnect();
            }
        }
    }

    /** 匿名 GET 连接：手动跟随（逐跳校验的前提），超时来自配置，不带 cookie/鉴权头。 */
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

    /** 流式写盘，边下边数；超过 maxBytes 立即中止（不读满整个流）。 */
    private long streamToFile(HttpURLConnection conn, Path destination) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(destination)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new ApiException(413, "SIZE_EXCEEDED", "下载内容超过上限 " + maxBytes + " 字节");
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }

    private static ApiException fetchFailed(IOException e) {
        String reason = e instanceof SocketTimeoutException ? "下载超时" : "下载失败";
        return new ApiException(502, "FETCH_FAILED", reason + "：" + e.getMessage());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("中断的下载残留临时文件未清理：{}（{}）", file, e.getMessage());
        }
    }
}
