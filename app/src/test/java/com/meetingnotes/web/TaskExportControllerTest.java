package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.18/T4.102：导出 include 解析（省略→默认五节含待办；空/全空→400；未知值→400 列合法值；
 * 输出按固定节序）+ 转写节文本（标点文本按句换行 / 无标点原样）。
 */
class TaskExportControllerTest {

    @Test
    void omittedIncludeFallsBackToDefaultFiveSectionsIncludingTodos() {
        // 仅参数**省略**（null）回落默认；传了但空/全空必须 400（见 emptyOrAllCommasFail400）
        // T4.102 语义翻转：默认集由四节改为五节（含待办），观察期结束
        Set<String> omitted = TaskExportController.parseInclude(null);

        assertEquals(Set.of("summary", "keywords", "keypoints", "transcript", "todos"), omitted,
                "默认 = 全部五节（T4.102 起含待办）");
    }

    /** T4.102/C7-1：默认集按固定节序排列，待办在最后。 */
    @Test
    void omittedIncludeKeepsFixedOrderWithTodosLast() {
        var ordered = TaskExportController.parseInclude(null);
        var iter = ordered.iterator();
        assertEquals("summary", iter.next());
        assertEquals("keywords", iter.next());
        assertEquals("keypoints", iter.next());
        assertEquals("transcript", iter.next());
        assertEquals("todos", iter.next());
        assertFalse(iter.hasNext(), "默认集恰好五节");
    }

    @Test
    void explicitIncludeParsesAndDeduplicates() {
        assertEquals(Set.of("summary", "keywords", "keypoints", "transcript", "todos"),
                TaskExportController.parseInclude("summary, keywords ,keypoints,transcript,todos"));
        assertEquals(Set.of("transcript"), TaskExportController.parseInclude("transcript,transcript, transcript"));
    }

    @Test
    void unknownValueFails400WithValidValuesInMessage() {
        ApiException e = assertThrows(ApiException.class, () -> TaskExportController.parseInclude("summary,foo"));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        assertTrue(e.getMessage().contains("summary,keywords,keypoints,transcript,todos"),
                "错误消息必须列出合法值：" + e.getMessage());
    }

    @Test
    void emptyOrAllCommasFail400() {
        ApiException empty = assertThrows(ApiException.class, () -> TaskExportController.parseInclude(""));
        ApiException spaces = assertThrows(ApiException.class, () -> TaskExportController.parseInclude("   "));
        ApiException commas = assertThrows(ApiException.class, () -> TaskExportController.parseInclude(",, ,"));
        assertEquals(400, empty.getStatus());
        assertEquals(400, spaces.getStatus());
        assertEquals(400, commas.getStatus());
        assertTrue(commas.getMessage().contains("不能为空"), commas.getMessage());
    }

    @Test
    void parseIncludeKeepsFixedSectionOrder() {
        // 输入乱序 → 输出按固定节序（摘要→关键词→要点→转写→待办）
        var ordered = TaskExportController.parseInclude("todos,transcript,summary");
        var iter = ordered.iterator();
        assertEquals("summary", iter.next());
        assertEquals("transcript", iter.next());
        assertEquals("todos", iter.next());
    }

    /** 标点文本 → 按句换行（保留标点在句尾、去首尾空白、空句丢弃）。 */
    @Test
    void punctuatedTranscriptBecomesOneSentencePerLine() {
        String out = TaskExportController.transcriptForExport(
                " 大家好，今天开会。第二点请注意！还有一个问题吗 最后总结一下。 ",
                "原始无标点文本");
        assertEquals("大家好，今天开会。\n第二点请注意！\n还有一个问题吗 最后总结一下。", out);
    }

    /** 无标点文本（punctuated null）→ 原样返回原始转写。 */
    @Test
    void fallsBackToRawTranscriptWhenPunctuatedMissing() {
        assertEquals("原始无标点文本",
                TaskExportController.transcriptForExport(null, "原始无标点文本"));
        assertEquals("原始无标点文本",
                TaskExportController.transcriptForExport("", "原始无标点文本"));
        assertEquals("", TaskExportController.transcriptForExport(null, "  "));
    }

    /* ===== T4.43：单条导出 Content-Disposition 优先展示名 ===== */

    @Test
    void contentDispositionPrefersDisplayNameWithRfc5987ForNonAscii() {
        String header = TaskExportController.contentDisposition(7L, "第三章 讲座");
        assertTrue(header.startsWith("attachment;"), "仍是附件下载：" + header);
        assertTrue(header.contains("filename=\"task-7.md\""), "非 ASCII 时 filename 用 id 兜底：" + header);
        assertTrue(header.contains("filename*=UTF-8''"), "RFC 5987 filename* 承载中文名：" + header);
        assertTrue(header.contains("%E7%AC%AC%E4%B8%89%E7%AB%A0"), "中文名百分号编码：" + header);
        assertTrue(header.endsWith(".md"), header);
    }

    @Test
    void contentDispositionAsciiNameAndFallback() {
        assertEquals("attachment; filename=\"weekly note.md\"; filename*=UTF-8''weekly%20note.md",
                TaskExportController.contentDisposition(9L, "weekly note"),
                "ASCII 展示名（含空格）直接作为 filename 兜底 + filename* 双写（空格编码 %20）");
        assertEquals("attachment; filename=\"task-9.md\"",
                TaskExportController.contentDisposition(9L, null), "无展示名 → 原 task-<id>.md 口径");
        assertEquals("attachment; filename=\"task-9.md\"",
                TaskExportController.contentDisposition(9L, "   "), "全空白展示名 → 原口径");
        // 非法字符经安全化后进入 header（: → _），不破坏 header 结构
        String sanitized = TaskExportController.contentDisposition(11L, "a:b");
        assertTrue(sanitized.contains("filename=\"a_b.md\""), sanitized);
    }
}
