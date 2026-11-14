package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T3.9 模板解析：显式优先（大小写不敏感）→ 文件名关键字推荐（优先级固定
 * shortvideo → course → interview → podcast → meeting，命中即短路）→ 默认 meeting；
 * 非法显式值 400 INVALID_PARAM、message 逐字固定（不静默回落，US-12 case 4）；
 * 文本路径不做文件名推荐。
 */
class TemplateResolverTest {

    @Test
    void explicitValueWinsOverFilenameRecommendation() {
        TemplateResolver.Resolution r = TemplateResolver.resolve("course", "腾讯会议_20261115.wav");
        assertEquals("course", r.template());
        assertEquals("显式", r.source(), "显式指定必须盖过文件名推荐");
    }

    @Test
    void explicitValueIsTrimmedAndCaseInsensitive() {
        TemplateResolver.Resolution r = TemplateResolver.resolve("  PODCAST ", null);
        assertEquals("podcast", r.template(), "trim + toLowerCase(Locale.ROOT)");
        assertEquals("显式", r.source());
    }

    @Test
    void invalidExplicitValueRejectedWith400AndExactMessage() {
        ApiException e = assertThrows(ApiException.class,
                () -> TemplateResolver.resolve("weekly", "会议.wav"));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        assertEquals("template 只支持 meeting/course/interview/podcast/shortvideo", e.getMessage(),
                "错误提示逐字固定（D-085 契约）");
    }

    @Test
    void filenamePriorityShortvideoFirst() {
        // 同一文件名命中多组关键字 → 优先级表顺序短路：shortvideo 胜过 course/interview/meeting
        TemplateResolver.Resolution r = TemplateResolver.resolve(null, "抖音网课访谈例会.mp3");
        assertEquals("shortvideo", r.template());
        assertEquals("文件名", r.source());
    }

    @Test
    void filenamePriorityCourseOverInterviewAndMeeting() {
        TemplateResolver.Resolution r = TemplateResolver.resolve(null, "网课访谈例会.wav");
        assertEquals("course", r.template());
    }

    @Test
    void everyTemplateHasKeywordHitFromFilename() {
        assertEquals("meeting", TemplateResolver.resolve(null, "腾讯会议_20261115.wav").template());
        assertEquals("course", TemplateResolver.resolve(null, "公开课Lecture03.mp3").template());
        assertEquals("interview", TemplateResolver.resolve(null, "面试_张三.m4a").template());
        assertEquals("podcast", TemplateResolver.resolve(null, "第12期播客.mp3").template());
        assertEquals("shortvideo", TemplateResolver.resolve(null, "B站_短视频素材.mp4").template(),
                "bilibili/b站 关键词大小写不敏感（文件名先转小写再包含匹配）");
    }

    @Test
    void noKeywordMatchFallsBackToMeetingWithDefaultSource() {
        TemplateResolver.Resolution r = TemplateResolver.resolve(null, "recording_20261115.wav");
        assertEquals("meeting", r.template());
        assertEquals("默认", r.source(), "未命中必须可追溯为默认，不得伪装成文件名推荐");
    }

    @Test
    void textPathNeverRecommendsFromFilename() {
        // 文本 PASTE / 字幕：显式值否则 meeting，没有"文件名"来源
        assertEquals(new TemplateResolver.Resolution("interview", "显式"),
                TemplateResolver.resolveText("INTERVIEW "));
        assertEquals(new TemplateResolver.Resolution("meeting", "默认"),
                TemplateResolver.resolveText(null));
        assertEquals(new TemplateResolver.Resolution("meeting", "默认"),
                TemplateResolver.resolveText("  "));
    }

    @Test
    void blankExplicitValueBehavesLikeUnspecified() {
        // 显式空白串视同未指定（不报 400）：音频继续走文件名推荐，文本走默认
        assertEquals("meeting", TemplateResolver.resolve(" ", "腾讯会议.wav").template());
        assertEquals("文件名", TemplateResolver.resolve(" ", "腾讯会议.wav").source());
    }
}
