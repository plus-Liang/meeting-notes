package com.meetingnotes.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 链接导入请求体：{"url":"https://...", "use_byof":true}。
 *
 * <p>T4.32a：url 必填（音/视频直链）；T4.32b 增可选 {@code use_byof}（默认 false，
 * 既有请求体不含该键 → 语义不变）：true 表示用外部抓取工具（FETCHER_CMD）抓取，
 * 未配置 → 400 FETCHER_UNAVAILABLE。网页链接（text/html）无 BYOF → 400 NOT_DIRECT_MEDIA。
 */
public record LinkRequest(String url, @JsonProperty("use_byof") Boolean useByof) {

    /** use_byof 归一化：null/缺省 = false（T4.32a 既有语义不变）。 */
    public boolean useByofRequested() {
        return Boolean.TRUE.equals(useByof);
    }
}
