package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 抽音轨（T3.7 从 {@link TranscriptionWorker} 抽出，保持 worker ≤300 行闸）：
 * 任意输入容器 → 16k 单声道 wav（卡片指定参数，D-010）。
 *
 * <p>T4.76/P-04：看门狗 + 旁路排空。旧实现「先 {@code readAllBytes} 再 {@code waitFor(10min)}」
 * 有两个致命点：① {@code redirectErrorStream(true)} 把 stderr 并进 stdout，ffmpeg 挂住不退出时
 * 读流无限阻塞；② {@code waitFor} 排在阻塞读之后永远轮不到 ⇒ 真实超时预算 = 无限，一条坏音频
 * （坏容器/网络流）就能把单线程 worker 永久占死。现结构照抄 {@code fetch/ProcessMediaFetcher}
 * 的已验证范本：{@code redirectErrorStream(false)}；stderr/stdout 各由守护线程旁路持续排空
 * （stderr 只留尾部）；看门狗线程从进程启动起算 timeoutSec、到点 {@code destroyForcibly()} 让
 * 阻塞读自然 EOF；主线程只 {@code waitFor(剩余)}，绝不阻塞在无界读取上。
 */
@Component
public class MonoWavExtractor {

    private static final Logger log = LoggerFactory.getLogger(MonoWavExtractor.class);
    /** stderr 只留尾部（超长日志截旧保新），失败时进异常消息（范本同款口径）。 */
    private static final int STDERR_TAIL_KEEP = 2048;

    private final String ffmpegBin;
    private final long timeoutSec;

    public MonoWavExtractor(@Value("${app.media.ffmpeg-bin:ffmpeg}") String ffmpegBin,
                            @Value("${app.media.ffmpeg-timeout-sec:600}") long timeoutSec) {
        this.ffmpegBin = ffmpegBin;
        this.timeoutSec = timeoutSec;
    }

    public void extract(Path input, Path wav) throws Exception {
        Process p = new ProcessBuilder(
                ffmpegBin, "-y", "-i", input.toAbsolutePath().toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-f", "wav",
                wav.toAbsolutePath().toString())
                .redirectErrorStream(false)
                .start();
        StringBuilder stderrTail = new StringBuilder();
        Thread stderrDrain = drain(p.getErrorStream(), stderrTail);
        Thread stdoutDrain = drain(p.getInputStream(), new StringBuilder()); // stdout 只有日志，排空即弃
        java.util.concurrent.atomic.AtomicBoolean timedOut =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutSec * 1000L);
                timedOut.set(true);
                p.destroyForcibly(); // 到点强杀：让阻塞在管道上的任何一方自然 EOF
            } catch (InterruptedException ignored) {
                // 正常完成路径提前结束看门狗
            }
        }, "ffmpeg-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        boolean finished;
        try {
            finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            joinQuietly(stderrDrain);
            joinQuietly(stdoutDrain);
            throw new IllegalStateException("ffmpeg 抽音轨被中断");
        }
        watchdog.interrupt();
        if (!finished) {
            p.destroyForcibly();
        }
        joinQuietly(stderrDrain);
        joinQuietly(stdoutDrain);
        if (timedOut.get() || !finished) {
            throw new IllegalStateException("ffmpeg 抽音轨超时（上限 " + timeoutSec + " 秒）已终止");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg 失败：" + tail(stderrTail.toString(), 300));
        }
        log.debug("ffmpeg 抽音轨完成：{}", wav.getFileName());
    }

    /** 旁路线程持续排空流（防子进程因管道塞满卡死）；tail 非空时累积，超限只留尾部（范本同款）。 */
    private Thread drain(InputStream in, StringBuilder tail) {
        Thread t = new Thread(() -> {
            try (InputStream is = in) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) > 0) {
                    if (tail.length() >= STDERR_TAIL_KEEP) {
                        tail.delete(0, tail.length() - STDERR_TAIL_KEEP); // 只留尾部，防超长刷爆内存
                    }
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 进程终止时流关闭，属正常路径
            }
        }, "ffmpeg-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
