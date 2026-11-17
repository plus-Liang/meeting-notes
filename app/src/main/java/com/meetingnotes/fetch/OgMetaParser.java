package com.meetingnotes.fetch;

/**
 * og 元信息解析纯函数（T4.32b）：从页面 HTML（可能被 2MB 截断、可能是乱码/非 HTML）的
 * &lt;head&gt; 区提取 og:title / og:image / og:video:duration（兼容 og:audio:duration 与
 * itemprop="duration"）；无 og 回退 &lt;title&gt;；都没有 title=null。
 *
 * <p>卡面纪律：**纯函数、不许抛**——输入是外部世界返回的任意字节解码结果，截断/乱码/畸形
 * 标签一律静默返回能解析到的部分（解析不到就是 null），绝不向上抛异常。
 * 实现：正则提取（仓库无 HTML 解析依赖，也不为此引入），属性顺序两种写法都兼容
 * （property 在前 / content 在前）。HTML 实体只做最常见的五个（&amp; &lt; &gt; &quot; &#39;）。
 */
public final class OgMetaParser {

    private OgMetaParser() {
    }

    /** 解析结果：title/image 原文（image 可能是相对路径，由调用方按 final_url 补全）、时长秒（解析不出为 null）。 */
    public record OgMeta(String title, String image, Long durationSec) {
    }

    public static OgMeta parse(String html) {
        if (html == null || html.isBlank()) {
            return new OgMeta(null, null, null);
        }
        String title = firstMetaContent(html, "og:title");
        if (title == null) {
            title = firstTitleTag(html);
        }
        String image = firstMetaContent(html, "og:image");
        Long duration = parseDurationSec(
                firstMetaContent(html, "og:video:duration"));
        if (duration == null) {
            duration = parseDurationSec(firstMetaContent(html, "og:audio:duration"));
        }
        if (duration == null) {
            duration = parseDurationSec(firstMetaContent(html, "duration", "itemprop"));
        }
        return new OgMeta(title, image, duration);
    }

    /** 提取 &lt;meta property="X" content="V"&gt;（content 与 property 两种属性顺序都兼容）。 */
    private static String firstMetaContent(String html, String property) {
        return firstMetaContent(html, property, "property");
    }

    private static String firstMetaContent(String html, String key, String attr) {
        // content 在 key 之后
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<meta[^>]*" + attr + "=[\"']" + java.util.regex.Pattern.quote(key)
                                + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (m.find()) {
            return decodeEntities(m.group(1)).trim();
        }
        // content 在 key 之前
        m = java.util.regex.Pattern
                .compile("<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*" + attr + "=[\"']"
                                + java.util.regex.Pattern.quote(key) + "[\"']",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (m.find()) {
            return decodeEntities(m.group(1)).trim();
        }
        return null;
    }

    /** 无 og:title 时回退 &lt;title&gt;（截断的未闭合标签匹配不上就返回 null，不抛）。 */
    private static String firstTitleTag(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE
                        | java.util.regex.Pattern.DOTALL)
                .matcher(html);
        if (m.find()) {
            String raw = decodeEntities(m.group(1)).trim();
            return raw.isEmpty() ? null : raw;
        }
        return null;
    }

    /** ISO 8601 时长各段的秒数倍率（天/时/分/秒）——M4 阈值口径：写成命名常量。 */
    private static final long SEC_PER_DAY = 86400L;
    private static final long SEC_PER_HOUR = 3600L;
    private static final long SEC_PER_MINUTE = 60L;
    /** 脏数据上限：负数或超过 24h 的时长不采信。 */
    private static final long MAX_PLAUSIBLE_SEC = SEC_PER_DAY;

    /**
     * 时长值两种格式（卡面）：ISO 8601（PT1M2S / PT90S / PT1H2M3S）或纯秒数（"62" / "61.5"）。
     * 解析不出/负数/离谱大（&gt; 24h）→ null。秒数向上取整。
     */
    static Long parseDurationSec(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.matches("\\d+(\\.\\d+)?")) {
            return ceilSeconds(Double.parseDouble(v));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^P(?!$)(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$")
                .matcher(v);
        if (!m.find()) {
            return null;
        }
        double sec = 0;
        boolean any = false;
        String[] groups = {m.group(1), m.group(2), m.group(3), m.group(4)};
        long[] multipliers = {SEC_PER_DAY, SEC_PER_HOUR, SEC_PER_MINUTE, 1L};
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] != null) {
                sec += Double.parseDouble(groups[i]) * multipliers[i];
                any = true;
            }
        }
        return any ? ceilSeconds(sec) : null;
    }

    private static Long ceilSeconds(double sec) {
        if (sec < 0 || sec > MAX_PLAUSIBLE_SEC) {
            return null; // 负数/超过 24h 视为脏数据，不采信
        }
        return (long) Math.ceil(sec);
    }

    /** 只解最常见的五个命名/数字实体；其余原样保留（不许抛）。 */
    static String decodeEntities(String s) {
        if (s == null || !s.contains("&")) {
            return s;
        }
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
