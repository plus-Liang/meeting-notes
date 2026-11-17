package com.meetingnotes.upload;

import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 用 ffprobe 读取媒体真实时长（秒）。
 *
 * <p>容器内已装 ffmpeg（Dockerfile，D-010：媒体处理全在容器内）；二进制名走环境变量
 * {@code FFPROBE_BIN}（spec N11），默认 PATH 上的 ffprobe。
 *
 * <p><b>T4.30b（K-82）</b>：MediaRecorder 产出的流式 webm（浏览器录音）EBML 头部没有
 * duration，ffprobe {@code format=duration} 会输出 {@code N/A} 且 <b>exit 0</b>——
 * 「命令成功」≠「输出可解析」（K-82），旧实现直接 {@code Double.parseDouble} 会未捕获
 * NumberFormatException → 500。现主路径不可解析时兜底取<b>末音频包</b>
 * {@code pts_time(+duration_time)}；两路都拿不到才 400 INVALID_MEDIA（不再 500）。
 * 文本 → 时长的解析抽成纯函数（{@link #parseFormatDuration} /
 * {@link #durationFromPackets} / {@link #resolveDuration}），单测不依赖宿主机 ffprobe。
 */
@Component
public class AudioDurationProbe {

    private final String ffprobeBin;

    public AudioDurationProbe(@Value("${app.media.ffprobe-bin:ffprobe}") String ffprobeBin) {
        this.ffprobeBin = ffprobeBin;
    }

    /** 解析失败（含文件损坏）一律 400 INVALID_MEDIA，符合 spec US-1 场景②「接受阶段被拒且给原因」。 */
    public double durationSeconds(Path file) {
        String main = runFfprobe(List.of(
                ffprobeBin, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                file.toAbsolutePath().toString()));
        Double sec = resolveDuration(main, () -> runFfprobe(List.of(
                ffprobeBin, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "packet=pts_time,duration_time",
                "-of", "csv=p=0",
                file.toAbsolutePath().toString())));
        if (sec != null) {
            return sec;
        }
        throw new ApiException(400, "INVALID_MEDIA",
                "无法解析媒体时长（容器元数据与音频包时间均不可用，文件可能损坏）");
    }

    /**
     * 主路径输出 → 秒。空 / {@code N/A}（任意大小写）/ 非数字 / 非有限值 /
     * 本地化小数点（如 {@code 12,5}）一律返回 null，<b>不抛异常</b>（K-82）。
     */
    static Double parseFormatDuration(String output) {
        if (output == null) {
            return null;
        }
        String s = output.trim();
        if (s.isEmpty() || s.contains("\n") || s.contains("\r")) {
            return null; /* format=duration csv 输出只应有一个值；多行属异常输出，交兜底 */
        }
        return parseNumber(s);
    }

    /**
     * 兜底输出 → 秒：逐行 {@code pts_time[,duration_time]}，取<b>最后一行</b>有数值
     * pts_time 的行；有 duration_time 且为数值就加上；末行截断/非数字则回退更早的有效行。
     */
    static Double durationFromPackets(String output) {
        if (output == null) {
            return null;
        }
        Double result = null;
        for (String line : output.split("\r?\n")) {
            String[] parts = line.trim().split(",");
            Double pts = parseNumber(parts[0]);
            if (pts == null || pts < 0) {
                continue;
            }
            Double dur = parts.length > 1 ? parseNumber(parts[1]) : null;
            result = pts + (dur != null ? dur : 0.0);
        }
        return result;
    }

    /**
     * 两路裁决：主路径可解析直接用（<b>兜底不触发</b>，supplier 不被求值）；
     * 否则求值兜底输出再解析；都拿不到返回 null。
     */
    static Double resolveDuration(String mainOutput, Supplier<String> fallbackOutput) {
        Double d = parseFormatDuration(mainOutput);
        if (d != null) {
            return d;
        }
        return durationFromPackets(fallbackOutput.get());
    }

    /** 单值数值解析：空 / N/A / 非数字 / 非有限值 → null（K-82：外部工具输出不许裸 parseDouble）。 */
    private static Double parseNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || "N/A".equalsIgnoreCase(t)) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 跑一次 ffprobe（30s 超时）。exit 0 且有输出 → 返回 stdout；
     * 非 0 退出 / 超时 / 空输出按现有语义 400 INVALID_MEDIA（文件损坏类）；
     * 进程启动失败 / 被中断按现有语义 500（PROBE_UNAVAILABLE / PROBE_INTERRUPTED）。
     * 受检异常在内部包装成 ApiException，使兜底可用 {@code Supplier<String> 传递（K-82 结构）}。
     */
    private String runFfprobe(List<String> args) {
        Process p = null;
        try {
            p = new ProcessBuilder(args).start();
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
            return out;
        } catch (IOException e) {
            /* 带上底层原因（如 error=2 找不到二进制 / error=11 fork 重试）：运维可判定，不等同吞掉栈 */
            throw new ApiException(500, "PROBE_UNAVAILABLE",
                    "时长探测工具不可用：" + ffprobeBin + "（" + e + "）");
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
