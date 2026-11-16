package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.18：导出 include 解析（省略→默认四节不含待办；空/全空→400；未知值→400 列合法值；
 * 输出按固定节序）+ 转写节文本（标点文本按句换行 / 无标点原样）。
 */
class TaskExportControllerTest {

    @Test
    void omittedIncludeFallsBackToDefaultFourSectionsWithoutTodos() {
        // 仅参数**省略**（null）回落默认；传了但空/全空必须 400（见 emptyOrAllCommasFail400）
        Set<String> omitted = TaskExportController.parseInclude(null);

        assertEquals(Set.of("summary", "keywords", "keypoints", "transcript"), omitted,
                "默认 = 摘要+关键词+要点+转写（待办观察期不含，D-109）");
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
}
