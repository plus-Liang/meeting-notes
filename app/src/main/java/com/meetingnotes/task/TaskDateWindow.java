package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 任务列表日期区间过滤参数（T4.42 / D-160）：{@code created_after}/{@code created_before}
 * 解析为半开区间 [after, before)——{@code YYYY-MM-DD} 按**服务端时区**解释：
 * after = 当日 00:00 起；before = **次日** 00:00 止（{@code before == after} 同一天合法，含当天全天）。
 * 两侧都空 → null（不过滤）；格式非法或 after 日期晚于 before → 400 INVALID_PARAM（口径单一，不散落两处）。
 * 从 TaskQueryService 独立成类：M3 300 行上限（先例同 TaskCountsController 的拆分纪律）。
 */
public record TaskDateWindow(OffsetDateTime after, OffsetDateTime before) {

    /**
     * 解析 created_after / created_before。public static：controller 调用 + 单测直测。
     * 只给一端合法；两端都给时按日期比较（after 日期 > before 日期 → 400）。
     */
    public static TaskDateWindow resolve(String createdAfter, String createdBefore) {
        LocalDate afterDate = parseDateParam(createdAfter, "created_after");
        LocalDate beforeDate = parseDateParam(createdBefore, "created_before");
        if (afterDate == null && beforeDate == null) {
            return null;
        }
        if (afterDate != null && beforeDate != null && afterDate.isAfter(beforeDate)) {
            throw new ApiException(400, "INVALID_PARAM", "created_after 不能晚于 created_before");
        }
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime after = afterDate == null ? null
                : afterDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime before = beforeDate == null ? null
                : beforeDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return new TaskDateWindow(after, before);
    }

    /** 日期参数解析：空串按未给；非 ISO {@code yyyy-MM-dd}（含 2026-2-3 这类宽松写法）→ 400。 */
    private static LocalDate parseDateParam(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "INVALID_PARAM", name + " 必须是 YYYY-MM-DD 格式");
        }
    }
}
