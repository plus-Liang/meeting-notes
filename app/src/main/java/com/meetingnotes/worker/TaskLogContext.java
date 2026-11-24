package com.meetingnotes.worker;

import org.slf4j.MDC;

/**
 * worker 侧日志上下文（T4.86/P-26）：统一写/清 MDC 的 taskId 与 requestId，
 * 取代散落的 MDC.put/remove——JSON 结构化日志下这两个键就是顶层字段（T2.7 机制）。
 *
 * <p>口径：open 只收非空白键值（null/空白跳过，**绝不写 "null" 字符串**——历史任务
 * 没有 request_id，worker 日志里就不应出现该键）；close 只移除本类管的两个键，
 * **不用 clear()**——其它组件也可能在用 MDC，clear 会误伤。
 */
final class TaskLogContext {

    private TaskLogContext() {
    }

    /** 写入 taskId（必填语义）与 requestId（可空：历史任务无值则跳过，不留空键）。 */
    static void open(String taskId, String requestId) {
        if (taskId != null && !taskId.isBlank()) {
            MDC.put("taskId", taskId);
        }
        if (requestId != null && !requestId.isBlank()) {
            MDC.put("requestId", requestId);
        }
    }

    /** 移除本类管理的两个键（不用 clear()——不误伤其它组件的 MDC 键）。 */
    static void close() {
        MDC.remove("taskId");
        MDC.remove("requestId");
    }
}
