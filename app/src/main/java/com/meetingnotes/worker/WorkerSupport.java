package com.meetingnotes.worker;

import java.util.concurrent.TimeUnit;

/**
 * T4.100/C4：worker 的无状态小工具（从 TranscriptionWorker 抽出以守住 300 行门禁 M3）。
 * 两个方法行为逐字不变，仅移动位置——不改变任何语义。
 */
final class WorkerSupport {
    private WorkerSupport() {}

    /** 失败原因截 200 字（超长省略号），null/空白归一化为 UNKNOWN。 */
    static String abbreviate(String s) {
        return s == null || s.isBlank() ? "UNKNOWN" : s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** Redis 取队列异常后静默退避（interrupt 时恢复标志）；时长随 pollTimeout 走。 */
    static void sleepQuietly(java.time.Duration pollTimeout) {
        try { TimeUnit.SECONDS.sleep(pollTimeout.getSeconds()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
