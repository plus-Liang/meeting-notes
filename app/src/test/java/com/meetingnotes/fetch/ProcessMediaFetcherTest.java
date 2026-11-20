package com.meetingnotes.fetch;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4.32b：ProcessMediaFetcher 三态单测（卡判据：成功/超时/非 0）+ stdout 模式 + 超大小 +
 * 未配置 FETCHER_UNAVAILABLE + {url} 占位符纪律 + 入口 URL 过 UrlGuard。
 * 命令用平台 shell 跑最小脚本（Windows cmd / Unix sh），不依赖任何外部下载工具。
 */
class ProcessMediaFetcherTest {

    @TempDir
    Path tmpDir;

    private UrlGuard guard;

    @BeforeEach
    void setUp() {
        guard = mock(UrlGuard.class);
        when(guard.verify(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private ProcessMediaFetcher fetcher(String cmd, long timeoutMs, long maxBytes) {
        return new ProcessMediaFetcher(guard, cmd, timeoutMs, maxBytes);
    }

    @Test
    void unconfiguredFetcherRejectsWithFetcherUnavailable() {
        ProcessMediaFetcher f = fetcher("   ", 5000, 1024 * 1024);
        assertFalse(f.isConfigured());
        ApiException e = assertThrows(ApiException.class,
                () -> f.fetch("http://example.com/page", tmpDir.resolve("out.bin")));
        assertEquals(400, e.getStatus());
        assertEquals("FETCHER_UNAVAILABLE", e.getCode());
    }

    @Test
    void successWithOutPlaceholderWritesFile() throws Exception {
        Path src = tmpDir.resolve("src.wav");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path out = tmpDir.resolve("out.wav");
        String cmd = isWindows() ? "cmd.exe /c copy /y {url} {out}" : "cp {url} {out}";
        // {url} 传 src 路径（stub UrlGuard 原样放行），工具把它拷到 {out}
        MediaFetcher.FetchResult r = fetcher(cmd, 30000, 1024 * 1024)
                .fetch(src.toString(), out);
        assertEquals(5, r.sizeBytes());
        assertEquals(5, Files.size(out));
    }

    @Test
    void successWithoutOutPlaceholderStreamsStdoutToDisk() throws Exception {
        Path src = tmpDir.resolve("src.bin");
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        Files.write(src, data);
        Path out = tmpDir.resolve("out.bin");
        String cmd = isWindows() ? "cmd.exe /c type {url}" : "cat {url}";
        MediaFetcher.FetchResult r = fetcher(cmd, 30000, 1024 * 1024)
                .fetch(src.toString(), out);
        assertEquals(100, r.sizeBytes());
        assertTrue(java.util.Arrays.equals(data, Files.readAllBytes(out)), "stdout 内容原样落盘");
    }

    @Test
    void timeoutKilledAndReportsFetchFailed() {
        Path out = tmpDir.resolve("out.bin");
        String cmd = isWindows() ? "cmd.exe /c ping -n 30 {url}" : "ping -c 30 {url}";
        ApiException e = assertThrows(ApiException.class,
                () -> fetcher(cmd, 1000, 1024 * 1024).fetch("127.0.0.1", out));
        assertEquals(502, e.getStatus());
        assertEquals("FETCH_FAILED", e.getCode());
        assertTrue(e.getMessage().contains("超时"), "文案说明超时：" + e.getMessage());
        assertFalse(Files.exists(out), "超时后不留残留文件");
    }

    @Test
    void nonZeroExitReportsFetchFailedWithStderrTail() throws Exception {
        Path out = tmpDir.resolve("out.bin");
        String cmd;
        if (isWindows()) {
            cmd = "cmd.exe /c echo this-tool-failed-badly {url} 1>&2 & exit 3";
        } else {
            // 生产代码按空格拆 argv、不过 shell——写脚本文件保证确定性 stderr marker（不依赖 ls 的本地化文案）
            Path script = tmpDir.resolve("fail.sh");
            Files.write(script, "#!/bin/sh\nprintf 'this-tool-failed-badly\\n' >&2\nexit 3\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.setPosixFilePermissions(script,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            cmd = "sh " + script + " {url}";
        }
        ApiException e = assertThrows(ApiException.class,
                () -> fetcher(cmd, 30000, 1024 * 1024).fetch("/nonexistent-t432b/nothing", out));
        assertEquals(502, e.getStatus());
        assertEquals("FETCH_FAILED", e.getCode());
        assertTrue(e.getMessage().contains("this-tool-failed-badly"), "文案带 stderr 末尾：" + e.getMessage());
        assertFalse(Files.exists(out), "失败后不留残留文件");
    }

    @Test
    void stdoutOverMaxBytesAborts413AndDeletesPartial() throws Exception {
        Path src = tmpDir.resolve("big.bin");
        Files.write(src, new byte[2000]);
        Path out = tmpDir.resolve("out.bin");
        String cmd = isWindows() ? "cmd.exe /c type {url}" : "cat {url}";
        ApiException e = assertThrows(ApiException.class,
                () -> fetcher(cmd, 30000, 100).fetch(src.toString(), out));
        assertEquals(413, e.getStatus());
        assertEquals("SIZE_EXCEEDED", e.getCode());
        assertFalse(Files.exists(out), "超大小中止后删除半截文件");
    }

    /** {url} 必须是独立 argv 元素——拼进 --url={url} 这类 token 按配置错误拒绝（杜绝注入口子）。 */
    @Test
    void urlPlaceholderGluedIntoLargerTokenRejected() {
        Path out = tmpDir.resolve("out.bin");
        ApiException e = assertThrows(ApiException.class,
                () -> fetcher("tool --url={url} --out {out}", 30000, 1024 * 1024)
                        .fetch("http://example.com/page", out));
        assertEquals(500, e.getStatus());
        assertTrue(e.getMessage().contains("{url}"));
    }

    @Test
    void missingUrlPlaceholderRejected() {
        Path out = tmpDir.resolve("out.bin");
        ApiException e = assertThrows(ApiException.class,
                () -> fetcher("tool --out {out}", 30000, 1024 * 1024)
                        .fetch("http://example.com/page", out));
        assertEquals(500, e.getStatus());
        assertTrue(e.getMessage().contains("{url}"));
    }

    /** 入口 URL 仍先过 UrlGuard——BYOF 只是换了"怎么下载"，不换"允许下载哪"。 */
    @Test
    void entryUrlStillPassesUrlGuard() {
        UrlGuard blocking = mock(UrlGuard.class);
        when(blocking.verify(anyString())).thenThrow(new ApiException(400, "URL_BLOCKED", "内网"));
        ProcessMediaFetcher f = new ProcessMediaFetcher(blocking, "cp src {out}", 30000, 1024 * 1024);
        ApiException e = assertThrows(ApiException.class,
                () -> f.fetch("http://127.0.0.1/x", tmpDir.resolve("out.bin")));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
    }
}
