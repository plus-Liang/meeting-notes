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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * BYOF（Bring Your Own Fetcher，T4.32b）：用户自配外部抓取工具命令（app.fetch.fetcher-cmd /
 * 环境变量 FETCHER_CMD，默认空 = 未配置）来抓取平台页面里的媒体——**仓库不含任何平台解析器**
 * （D-027R）：不集成 yt-dlp、不执行 JS、不带 cookie、不伪造签名，工具由使用者自装、自负合规责任。
 *
 * <p>命令按空格拆分为 argv、**不过 shell**（{@link ProcessBuilder} 直接执行）：
 * 占位符 {@code {url}} 必填且必须是**独立的 argv 元素**（整体替换，杜绝注入——
 * 拼进更大的 token 会被判为配置错误拒绝启动请求）；{@code {out}} 可选（落盘路径），
 * 无则从 stdout 流式落盘。输出同样受 app.fetch.max-bytes 限制（超限 413 SIZE_EXCEEDED 并删残留）。
 * 超时（app.fetch.fetcher-timeout-ms，默认 120s）/ 非 0 退出 / 启动失败 → 502 FETCH_FAILED
 * （文案带 stderr 末 200 字）。
 *
 * <p>入口 URL 仍先过 {@link UrlGuard}（SSRF 防护不许跳过——BYOF 只是换了"怎么下载"，
 * 不换"允许下载哪"）。未配置 FETCHER_CMD 时任何请求 → 400 FETCHER_UNAVAILABLE（码表只增）。
 */
@Component("processMediaFetcher")
public class ProcessMediaFetcher implements MediaFetcher {

    private static final Logger log = LoggerFactory.getLogger(ProcessMediaFetcher.class);

    private final UrlGuard guard;
    private final String fetcherCmd;
    private final long timeoutMs;
    private final long maxBytes;

    @Autowired
    public ProcessMediaFetcher(UrlGuard guard,
                               @Value("${app.fetch.fetcher-cmd:}") String fetcherCmd,
                               @Value("${app.fetch.fetcher-timeout-ms}") long timeoutMs,
                               @Value("${app.fetch.max-bytes}") long maxBytes) {
        this.guard = guard;
        this.fetcherCmd = fetcherCmd;
        this.timeoutMs = timeoutMs;
        this.maxBytes = maxBytes;
    }

    /** FETCHER_CMD 是否已配置（/link/preview 的 byof_available 与 /tasks/link 的 use_byof 都打在消费点）。 */
    public boolean isConfigured() {
        return fetcherCmd != null && !fetcherCmd.isBlank();
    }

    @Override
    public FetchResult fetch(String url, Path destination) {
        if (!isConfigured()) {
            throw new ApiException(400, "FETCHER_UNAVAILABLE",
                    "未配置外部抓取工具（FETCHER_CMD），无法抓取页面媒体；请把媒体导出成文件后直接上传");
        }
        guard.verify(url); // 入口 URL 仍先过 UrlGuard（BYOF 不绕过 SSRF 防护）

        List<String> argv = buildArgv(url, destination);
        Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw fetchFailed("外部工具启动失败（检查 FETCHER_CMD 是否可执行）：" + e.getMessage(), "");
        }

        boolean hasOut = fetcherCmd.contains("{out}");
        StringBuilder stderrTail = new StringBuilder();
        Thread stderrDrain = drainStderr(process, stderrTail);
        // 看门狗：超时预算从**进程启动**起算（不管 {out} 模式还是 stdout 流模式），到点强杀——
        // stdout 流模式的 read 可能无限阻塞，光靠 waitFor(剩余) 兜不住
        java.util.concurrent.atomic.AtomicBoolean timedOut =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
                timedOut.set(true);
                process.destroyForcibly();
            } catch (InterruptedException ignored) {
                // 正常完成路径提前结束看门狗
            }
        }, "byof-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean finished = false;
        int exit;
        try {
            if (hasOut) {
                // {out} 模式：工具自己写文件，本进程只等结果（stderr 已在旁路线程排空防管道塞满）
                finished = process.waitFor(Math.max(1, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
            } else {
                // stdout 模式：主线程边读边落盘（受 max-bytes 限制）；工具超时被看门狗强杀后流自然 EOF
                streamToFileCap(process, destination);
                finished = process.waitFor(Math.max(1, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
            }
            exit = finished ? process.exitValue() : -1;
        } catch (IOException e) {
            // stdout 模式读流失败（管道断开等）：清残留后按网络失败语义报
            process.destroyForcibly();
            deleteQuietly(destination);
            throw fetchFailed("外部工具输出读取失败：" + e.getMessage(), tail(stderrTail));
        } catch (ApiException e) {
            // 413 等业务拒绝：终止子进程并清掉半截输出后原样上抛
            process.destroyForcibly();
            deleteQuietly(destination);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw fetchFailed("外部工具被中断", tail(stderrTail));
        } finally {
            watchdog.interrupt();
            if (!finished) {
                process.destroyForcibly();
            }
            joinQuietly(stderrDrain);
        }

        if (timedOut.get()) {
            // 看门狗到点强杀（此时 exit 值无意义，统一按超时语义报）
            deleteQuietly(destination);
            throw fetchFailed("外部工具超时（上限 " + timeoutMs + " 毫秒）已终止", tail(stderrTail));
        }
        if (!finished) {
            deleteQuietly(destination);
            throw fetchFailed("外部工具超时（上限 " + timeoutMs + " 毫秒）已终止", tail(stderrTail));
        }
        if (exit != 0) {
            deleteQuietly(destination);
            throw fetchFailed("外部工具异常退出（exit " + exit + "）", tail(stderrTail));
        }

        long size;
        try {
            if (hasOut) {
                if (!Files.exists(destination)) {
                    throw fetchFailed("外部工具退出码为 0 但未产出文件", tail(stderrTail));
                }
                size = Files.size(destination);
            } else {
                size = Files.size(destination);
            }
            if (size > maxBytes) {
                deleteQuietly(destination);
                throw new ApiException(413, "SIZE_EXCEEDED", "抓取内容超过上限 " + maxBytes + " 字节");
            }
        } catch (IOException e) {
            deleteQuietly(destination);
            throw fetchFailed("外部工具输出读取失败：" + e.getMessage(), tail(stderrTail));
        }
        log.info("BYOF 抓取完成：{} 字节（{}）", size, argv.get(0));
        return new FetchResult("application/octet-stream", size);
    }

    /**
     * 命令串 → argv：按空白拆分、不过 shell；{url} 必填且必须是**恰好等于** "{url}" 的独立元素
     * （出现于更大 token 内按配置错误拒绝，杜绝把 URL 拼进参数值里再被 shell 二次解释的口子）。
     * {out} 存在时替换为落盘路径。
     */
    private List<String> buildArgv(String url, Path destination) {
        List<String> argv = new ArrayList<>();
        boolean hasUrl = false;
        for (String token : fetcherCmd.trim().split("\\s+")) {
            if (token.contains("{url}")) {
                if (!token.equals("{url}")) {
                    throw configError("{url} 必须是独立参数元素（禁止拼进其它参数，如 --url={url}）");
                }
                argv.add(url);
                hasUrl = true;
            } else if (token.contains("{out}")) {
                if (!token.equals("{out}")) {
                    throw configError("{out} 必须是独立参数元素");
                }
                argv.add(destination.toAbsolutePath().toString());
            } else {
                argv.add(token);
            }
        }
        if (!hasUrl) {
            throw configError("FETCHER_CMD 缺少必填占位符 {url}");
        }
        return argv;
    }

    private ApiException configError(String message) {
        log.error("FETCHER_CMD 配置非法：{}", message);
        return new ApiException(500, "INTERNAL_ERROR", "FETCHER_CMD 配置非法：" + message + "（属部署配置问题，请联系管理员）");
    }

    /** stdout 流式落盘（无 {out} 模式）：边读边数，超 maxBytes 立即中止并终止子进程。 */
    private long streamToFileCap(Process process, Path destination) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = process.getInputStream(); OutputStream out = Files.newOutputStream(destination)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    process.destroyForcibly();
                    throw new ApiException(413, "SIZE_EXCEEDED", "抓取内容超过上限 " + maxBytes + " 字节");
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }

    /** 旁路线程持续排空 stderr（防子进程因 stderr 管道塞满而卡死），累积到上限即停。 */
    private Thread drainStderr(Process process, StringBuilder tail) {
        Thread t = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                    if (tail.length() > 4096) {
                        tail.delete(0, tail.length() - 2048); // 只留尾部，防超长刷爆内存
                    }
                }
            } catch (IOException ignored) {
                // 进程终止时流关闭，属正常路径
            }
        }, "byof-stderr-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String tail(StringBuilder sb) {
        String s = sb.toString();
        return s.length() <= 200 ? s : s.substring(s.length() - 200);
    }

    private static ApiException fetchFailed(String message, String stderrTail) {
        String extra = stderrTail == null || stderrTail.isBlank() ? "" : "；stderr 末尾：" + stderrTail;
        return new ApiException(502, "FETCH_FAILED", message + extra);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("BYOF 失败后临时文件未清理：{}（{}）", file, e.getMessage());
        }
    }
}
