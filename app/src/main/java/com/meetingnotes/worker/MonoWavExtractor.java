package com.meetingnotes.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 抽音轨（T3.7 从 {@link TranscriptionWorker} 抽出，保持 worker ≤300 行闸）：
 * 任意输入容器 → 16k 单声道 wav（卡片指定参数，D-010）。
 */
@Component
public class MonoWavExtractor {

    private final String ffmpegBin;

    public MonoWavExtractor(@Value("${app.media.ffmpeg-bin:ffmpeg}") String ffmpegBin) {
        this.ffmpegBin = ffmpegBin;
    }

    public void extract(Path input, Path wav) throws Exception {
        Process p = new ProcessBuilder(
                ffmpegBin, "-y", "-i", input.toAbsolutePath().toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-f", "wav",
                wav.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String out;
        try (var is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg 抽音轨超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg 失败：" + tail(out, 300));
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
