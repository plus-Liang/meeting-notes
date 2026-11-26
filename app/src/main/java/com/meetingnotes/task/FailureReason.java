package com.meetingnotes.task;

import java.util.Map;

/**
 * T4.95/C1（v0.2 失败可视）：稳定错误码 → 人话（title + 建议 hint）的纯映射。
 * D-239（一人一机自部署 ⇒ 用户即运维、页面就是唯一告警渠道）：失败任务在页面上必须能
 * 自解释「为什么失败 / 下一步做什么」。数据来源见 {@code DeadTaskRecorder}（DEAD 时
 * task.error_code + dead_letter.reason）与 {@code TranscriptionWorker.markFailure}（RETRYING
 * 时 error_code 也会写，但无 dead_letter 行）。
 *
 * <p>纯静态映射，无 Spring / JDBC 依赖（前端同文案映射在 task.html，两处必须同步改）。
 * 未知码绝不编造：title 原样返回码字面量，hint 用兜底文案（与 UNKNOWN_ERROR 一致）。
 */
public final class FailureReason {

    /** 人话条目：title = 一句话原因；hint = 用户下一步建议。 */
    public record Hint(String title, String hint) {
    }

    /** 兜底建议（未知码 / 空码共用，不编造具体原因）。 */
    private static final String FALLBACK_HINT = "把这段错误码复制给开发者，或查看容器日志。";

    private static final String UNKNOWN = "UNKNOWN_ERROR";

    /** 五个真实会出现的码（git grep 实测：TranscriptionWorker / TaskCrashRecovery / TaskRequeueSupport）。 */
    private static final Map<String, Hint> TEXTS = Map.of(
            "STORAGE_ERROR", new Hint("文件读取失败",
                    "检查对象存储是否可用、文件是否被移动或删除；重新提交即可重试。"),
            "ASR_ERROR", new Hint("语音识别失败",
                    "检查 ASR 容器是否健康（`/api/v1/admin/storage` 页或 `docker logs`）；换一种音频格式再试。"),
            "WORKER_CRASH", new Hint("处理进程异常中断",
                    "该任务连续中断已达上限；查看 `docker logs` 中 `taskId` 对应的 ERROR，必要时重启服务。"),
            "QUEUE_UNAVAILABLE", new Hint("队列不可用",
                    "Redis 可能不可用；恢复后点「重试」即可。"),
            UNKNOWN, new Hint("未知错误", FALLBACK_HINT));

    private FailureReason() {
    }

    /** 码 → 人话；null/空白/未知码不抛异常（未知码 title = 原文，hint = 兜底文案）。 */
    public static Hint of(String code) {
        if (code == null || code.isBlank()) {
            return TEXTS.get(UNKNOWN);
        }
        return TEXTS.getOrDefault(code, new Hint(code, FALLBACK_HINT));
    }
}
