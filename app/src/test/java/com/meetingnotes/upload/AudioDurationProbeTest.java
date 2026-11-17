package com.meetingnotes.upload;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.30b：外部工具输出 → 时长解析纯函数矩阵（K-82：ffprobe 对 MediaRecorder 流式 webm
 * 输出 format=duration=N/A 且 exit 0，旧实现裸 parseDouble 未捕获 → 500）。
 * 纯函数单测，不依赖宿主机 ffprobe。
 */
class AudioDurationProbeTest {

    // ==================== 主路径 format=duration ====================

    @Test
    void formatDuration_plainDecimalParses() {
        assertEquals(12.5, AudioDurationProbe.parseFormatDuration("12.5"));
    }

    @Test
    void formatDuration_surroundingWhitespaceTrimmed() {
        assertEquals(12.5, AudioDurationProbe.parseFormatDuration(" 12.5\n"));
        assertEquals(12.5, AudioDurationProbe.parseFormatDuration("12.5\n"));
    }

    @Test
    void formatDuration_naAnyCaseIsUnknown() {
        assertNull(AudioDurationProbe.parseFormatDuration("N/A"));
        assertNull(AudioDurationProbe.parseFormatDuration("n/a"));
    }

    @Test
    void formatDuration_blankAndNullAreUnknown() {
        assertNull(AudioDurationProbe.parseFormatDuration(""));
        assertNull(AudioDurationProbe.parseFormatDuration("   "));
        assertNull(AudioDurationProbe.parseFormatDuration(null));
    }

    @Test
    void formatDuration_localizedDecimalCommaIsUnknown() {
        /* ffprobe 本地化可能给 "12,5"：不猜语义（12.5? 125?），交兜底按音频包取（K-82 不猜外部输出） */
        assertNull(AudioDurationProbe.parseFormatDuration("12,5"));
    }

    @Test
    void formatDuration_nonNumericIsUnknown() {
        assertNull(AudioDurationProbe.parseFormatDuration("abc"));
    }

    @Test
    void formatDuration_nonFiniteIsUnknown() {
        /* parseDouble 其实认识 "NaN"/"Infinity"——数值上无效，必须按未知处理 */
        assertNull(AudioDurationProbe.parseFormatDuration("NaN"));
        assertNull(AudioDurationProbe.parseFormatDuration("Infinity"));
        assertNull(AudioDurationProbe.parseFormatDuration("-Infinity"));
    }

    @Test
    void formatDuration_multiLineIsUnknown() {
        assertNull(AudioDurationProbe.parseFormatDuration("1.0\n2.0"));
    }

    // ==================== 兜底：音频包 pts_time,duration_time ====================

    @Test
    void packets_lastRowPtsPlusDuration() {
        assertEquals(1.50, AudioDurationProbe.durationFromPackets(
                "0.000000,0.060000\n1.320000,0.060000\n1.440000,0.060000"), 1e-9,
                "取最后一行 pts+duration（验收侧实测：末包 1.44+0.06）");
    }

    @Test
    void packets_ptsOnlyWithoutDurationColumn() {
        assertEquals(7.92, AudioDurationProbe.durationFromPackets("7.92"), 1e-9,
                "只有 pts_time（无 duration_time）→ 直接用 pts");
    }

    @Test
    void packets_mixedRowsTakesLastValidRow() {
        assertEquals(2.5, AudioDurationProbe.durationFromPackets("0.5\n1.25,0.5\n2.0,0.5"), 1e-9,
                "pts-only 与 pts+duration 行混排，取最后有效行");
    }

    @Test
    void packets_truncatedLastLineFallsBackToEarlierValid() {
        /* 末行完全无法解析（如 N/A / 残缺非数字）→ 回退更早的有效行 */
        assertEquals(1.38, AudioDurationProbe.durationFromPackets(
                "0.000000,0.060000\n1.320000,0.060000\nN/A"), 1e-9);
        assertEquals(1.38, AudioDurationProbe.durationFromPackets(
                "0.000000,0.060000\n1.320000,0.060000\n1.44x"), 1e-9);
    }

    @Test
    void packets_truncatedNumericLineIsPtsOnly() {
        /* 字节截断把 ",0.060000" 切掉后剩 "1.440"：仍是合法数值 → 按 pts-only 采信（轻微低估可接受） */
        assertEquals(1.44, AudioDurationProbe.durationFromPackets(
                "0.000000,0.060000\n1.320000,0.060000\n1.440"), 1e-9);
    }

    @Test
    void packets_naRowsSkipped() {
        assertNull(AudioDurationProbe.durationFromPackets("N/A,N/A\nN/A"), "全 N/A → 未知");
        assertEquals(1.38, AudioDurationProbe.durationFromPackets(
                "1.320000,0.060000\nN/A,N/A\nN/A"), 1e-9, "尾部 N/A 行跳过，取更早有效行");
    }

    @Test
    void packets_blankAndNullAreUnknown() {
        assertNull(AudioDurationProbe.durationFromPackets(""));
        assertNull(AudioDurationProbe.durationFromPackets("   "));
        assertNull(AudioDurationProbe.durationFromPackets(null));
    }

    @Test
    void packets_negativePtsRejected() {
        assertNull(AudioDurationProbe.durationFromPackets("-1.0,0.5"), "负 pts 不当有效时长");
    }

    // ==================== 两路裁决 ====================

    @Test
    void resolve_mainPathUsableDoesNotInvokeFallback() {
        AtomicBoolean fallbackTouched = new AtomicBoolean(false);
        Double d = AudioDurationProbe.resolveDuration("12.5", () -> {
            fallbackTouched.set(true);
            throw new IllegalStateException("主路径可用时兜底不得被求值");
        });
        assertEquals(12.5, d);
        assertFalse(fallbackTouched.get(), "兜底 ffprobe 不应被调用（少一次进程开销）");
    }

    @Test
    void resolve_naFallsBackToPackets() {
        assertEquals(7.92, AudioDurationProbe.resolveDuration("N/A", () -> "7.92"), 1e-9);
    }

    @Test
    void resolve_bothUnknownReturnsNull() {
        assertNull(AudioDurationProbe.resolveDuration("", () -> "N/A"),
                "两路都拿不到 → null，由调用方统一 400 INVALID_MEDIA");
    }

    @Test
    void resolve_fallbackFailurePropagatesAsIs() {
        /* 兜底 ffprobe 本身失败（exit!=0 等）由 runFfprobe 抛 400——supplier 的异常不许被吞 */
        assertThrows(IllegalStateException.class,
                () -> AudioDurationProbe.resolveDuration("N/A", () -> {
                    throw new IllegalStateException("ffprobe exit!=0");
                }));
    }
}
