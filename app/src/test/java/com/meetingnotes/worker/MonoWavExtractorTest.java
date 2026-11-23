package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T4.76/P-04：抽音轨看门狗单测（跨平台假 ffmpeg 脚本，Windows 用 .bat、POSIX 用 .sh）。
 * 核心是「挂住且持续输出」用例——旧实现（先 readAllBytes 后 waitFor）正是被这个场景卡死：
 * 管道塞满 ⇒ 阻塞读永不返回。新实现靠 watchdog 到点 destroyForcibly + 旁路排空，超时必抛。
 */
class MonoWavExtractorTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path tmp;

    /** 写假 ffmpeg 脚本（bat/sh 按平台），返回可执行路径。 */
    private Path script(String posixBody, String batBody) throws Exception {
        Path p = tmp.resolve("fake-ffmpeg." + (WINDOWS ? "bat" : "sh"));
        if (WINDOWS) {
            Files.writeString(p, "@echo off\r\n" + batBody + "\r\n");
        } else {
            Files.writeString(p, "#!/bin/sh\n" + posixBody + "\n");
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return p;
    }

    private MonoWavExtractor extractor(Path script, long timeoutSec) {
        return new MonoWavExtractor(script.toAbsolutePath().toString(), timeoutSec);
    }

    /** 断言无残留假 ffmpeg 进程（强杀后进程终止是异步的，给 2s 容限轮询——不是对 sleep 的行为断言）。 */
    private void assertNoResidualProcess(Path script) throws Exception {
        String cmd = script.toAbsolutePath().toString();
        for (int i = 0; i < 8; i++) {
            boolean alive = ProcessHandle.allProcesses()
                    .anyMatch(ph -> ph.info().commandLine().orElse("").contains(cmd));
            if (!alive) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        fail("仍存在命令行含 " + cmd + " 的残留进程");
    }

    /** ①正常退出：立即 exit 0 → extract 正常返回。 */
    @Test
    void normalExitReturnsQuietly() throws Exception {
        Path s = script("exit 0", "exit /b 0");
        extractor(s, 600).extract(tmp.resolve("in.mp3"), tmp.resolve("out.wav"));
        assertNoResidualProcess(s);
    }

    /** ②挂住且持续输出（管道塞满场景）：timeoutSec=1 → 约 1s 抛「ffmpeg 抽音轨超时」。 */
    @Test
    void hangingWithOutputTimesOutAndKillsProcess() throws Exception {
        Path s = script("while true; do echo frame; done",
                "@:loop\r\necho frame\r\ngoto loop");
        MonoWavExtractor ex = extractor(s, 1);
        long start = System.currentTimeMillis();
        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> ex.extract(tmp.resolve("in.mp3"), tmp.resolve("out.wav")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 3000, "超时应在 3s 内触发，实际 " + elapsed + "ms");
        assertTrue(ex2.getMessage().contains("ffmpeg 抽音轨超时"), "实际消息：" + ex2.getMessage());
        assertTrue(ex2.getMessage().contains("上限 1 秒"), "实际消息：" + ex2.getMessage());
        assertNoResidualProcess(s);
    }

    /** ③挂住且零输出：证明超时不依赖「输出多」——看门狗按墙钟强杀。 */
    @Test
    void hangingSilentTimesOut() throws Exception {
        Path s = script("sleep 60", ":loop\r\ngoto loop");
        MonoWavExtractor ex = extractor(s, 1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ex.extract(tmp.resolve("in.mp3"), tmp.resolve("out.wav")));
        assertTrue(e.getMessage().contains("ffmpeg 抽音轨超时"), "实际消息：" + e.getMessage());
        assertNoResidualProcess(s);
    }

    /** ④非 0 退出 + stderr 输出：消息含「ffmpeg 失败」与 stderr 尾部内容。 */
    @Test
    void nonZeroExitReportsStderrTail() throws Exception {
        Path s = script("echo boom >&2\nexit 1", "echo boom 1>&2\r\nexit /b 1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> extractor(s, 600).extract(tmp.resolve("in.mp3"), tmp.resolve("out.wav")));
        assertTrue(e.getMessage().contains("ffmpeg 失败"), "实际消息：" + e.getMessage());
        assertTrue(e.getMessage().contains("boom"), "实际消息：" + e.getMessage());
        assertNoResidualProcess(s);
    }
}
