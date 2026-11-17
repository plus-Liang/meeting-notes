package com.meetingnotes.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * T4.32b：og 解析纯函数矩阵（卡判据逐条）——完整 og / 只有 &lt;title&gt; / og:image 相对路径 /
 * 时长变体（PT1M2S 与纯秒数）/ head 被 2MB 截断 / 非 HTML / 乱码不抛。
 */
class OgMetaParserTest {

    @Test
    void fullOgTagsExtracted() {
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"第三十七次周会\">"
                + "<meta property=\"og:image\" content=\"https://cdn.example.com/cover.jpg\">"
                + "<meta property=\"og:video:duration\" content=\"PT1M2S\">"
                + "</head></html>";
        OgMetaParser.OgMeta m = OgMetaParser.parse(html);
        assertEquals("第三十七次周会", m.title());
        assertEquals("https://cdn.example.com/cover.jpg", m.image());
        assertEquals(62L, m.durationSec());
    }

    @Test
    void onlyTitleTagFallsBack() {
        OgMetaParser.OgMeta m = OgMetaParser.parse("<html><head><title>  纯页面标题  </title></head></html>");
        assertEquals("纯页面标题", m.title());
        assertNull(m.image());
        assertNull(m.durationSec());
    }

    @Test
    void ogImageRelativePathKeptAsIs() {
        String html = "<meta property=\"og:image\" content=\"/static/covers/abc.jpg\">";
        OgMetaParser.OgMeta m = OgMetaParser.parse(html);
        assertEquals("/static/covers/abc.jpg", m.image()); // 相对路径不抛、原样返回（补全由服务层按 final_url 做）
    }

    @Test
    void durationPlainSecondsAndIsoVariants() {
        assertEquals(62L, OgMetaParser.parse(
                "<meta property=\"og:video:duration\" content=\"62\">").durationSec());
        assertEquals(90L, OgMetaParser.parse(
                "<meta property=\"og:video:duration\" content=\"PT90S\">").durationSec());
        assertEquals(3753L, OgMetaParser.parse(
                "<meta property=\"og:video:duration\" content=\"PT1H2M33S\">").durationSec());
        assertNull(OgMetaParser.parse(
                "<meta property=\"og:video:duration\" content=\"abc\">").durationSec());
    }

    @Test
    void ogAudioDurationAndItempropCompat() {
        assertEquals(61L, OgMetaParser.parse(
                "<meta property=\"og:audio:duration\" content=\"61\">").durationSec());
        assertEquals(61L, OgMetaParser.parse(
                "<meta itemprop=\"duration\" content=\"61\">").durationSec());
    }

    @Test
    void truncatedHeadDoesNotThrowAndKeepsEarlierMatches() {
        String full = "<meta property=\"og:title\" content=\"截断前的标题\">"
                + "<meta property=\"og:image\" content=\"https://cdn.example.com/very-long-cover-url.jpg\">";
        String truncated = full.substring(0, full.indexOf("og:image") + 30); // 标签中间被切
        OgMetaParser.OgMeta m = OgMetaParser.parse(truncated);
        assertEquals("截断前的标题", m.title());
        assertNull(m.image()); // 未闭合的残缺标签解析不到，静默为 null
    }

    @Test
    void nonHtmlAndGarbageDoNotThrow() {
        assertNull(OgMetaParser.parse("").title());
        assertNull(OgMetaParser.parse("   ").title());
        assertNull(OgMetaParser.parse("plain text, not html").title());
        assertNull(OgMetaParser.parse("<<<>>>&&&&\u0000\u0001随机乱码\u00FF").title());
        assertNull(OgMetaParser.parse("<title").title());
        assertNull(OgMetaParser.parse(null).title());
    }

    @Test
    void contentBeforePropertyOrderAndEntities() {
        String html = "<meta content=\"A &amp; B 公司季度复盘\" property=\"og:title\">";
        assertEquals("A & B 公司季度复盘", OgMetaParser.parse(html).title());
    }
}
