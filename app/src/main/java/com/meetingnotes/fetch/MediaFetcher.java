package com.meetingnotes.fetch;

import java.nio.file.Path;

/**
 * 媒体抓取抽象（T4.32a，BYOF 扩展点——T4.32b 的 FETCHER_CMD 在此接口上挂自定义实现）：
 * 给一个下载 URL 产出一个本地文件。
 *
 * <p>实现约定（默认实现 {@link HttpMediaFetcher}）：
 * ①入口 URL 与**每个**重定向跳都必须过 {@link UrlGuard}（SSRF 防护不许跳过）；
 * ②错误语义（卡面逐字）：
 *   格式/内网违规 → {@code ApiException} 400 {@code URL_BLOCKED}；
 *   网页链接（Content-Type: text/html）→ 400 {@code NOT_DIRECT_MEDIA}；
 *   下载内容超过大小上限 → 413 {@code SIZE_EXCEEDED}；
 *   网络失败/超时/远端错误码 → 502 {@code FETCH_FAILED}。
 * ③只发匿名 GET，不带 cookie / 鉴权头（卡禁止）。
 */
public interface MediaFetcher {

    /**
     * 下载 url 到 destination（覆盖写）。
     *
     * @param url         已经过调用方初步非空检查的直链（安全性校验在实现内逐跳执行）
     * @param destination 落盘目标文件（调用方负责临时文件生命周期）
     * @return 远端元数据
     * @throws com.meetingnotes.common.ApiException 按上述②的固定错误语义抛出
     */
    FetchResult fetch(String url, Path destination);

    /** 抓取结果元数据：远端声明的 Content-Type（可能带 charset 后缀）与落盘字节数。 */
    record FetchResult(String contentType, long sizeBytes) {
    }
}
