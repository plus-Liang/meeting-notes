package com.meetingnotes.upload;

import com.meetingnotes.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 用 ffprobe 读取媒体真实时长（秒）。
 *
 * <p>容器内已装 ffmpeg（Dockerfile，D-010：媒体处理全在容器内）；二进制名走环境变量
 * {@code FFPROBE_BIN}（spec N11），默认 PATH 上的 ffprobe。
 */
@Component
public class AudioDurationProbe {

    private final String ffprobeBin;

    public AudioDurationProbe(@Value("${app.media.ffprobe-bin:ffprobe}") String ffprobeBin) {
        this.ffprobeBin = ffprobeBin;
    }

    /** 解析失败（含文件损坏）一律抛 400 INVALID_MEDIA，符合 spec US-1 场景②「接受阶段被拒且给原因」。 */
    public double durationSeconds(Path file) {
        Process p = null;
        try {
            p = new ProcessBuilder(
                    ffprobeBin, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    file.toAbsolutePath().toString())
                    .start();
            String out;
            try (var is = p.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            String err;
            try (var es = p.getErrorStream()) {
                err = new String(es.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ApiException(400, "INVALID_MEDIA", "读取媒体时长超时");
            }
            if (p.exitValue() != 0 || out.isEmpty()) {
                throw new ApiException(400, "INVALID_MEDIA",
                        "无法解析媒体时长（文件可能损坏）：" + abbreviate(err));
            }
            return Double.parseDouble(out);
        } catch (IOException e) {
            throw new ApiException(500, "PROBE_UNAVAILABLE", "时长探测工具不可用：" + ffprobeBin);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "PROBE_INTERRUPTED", "时长探测被中断");
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static String abbreviate(String s) {
        if (s == null || s.isBlank()) {
            return "无详情";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
