package com.meetingnotes.asr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.35a：ASR 响应解析纯函数矩阵（卡判据逐条）——正常 segments / 无 segments 键 → null /
 * 空数组 → [] / 负数或乱序（end&lt;start）不采信 / 空 text / 非 JSON / 老响应只有三键 /
 * 元素缺字段不采信；断言不抛、不 500。
 */
class AsrResponseParserTest {

    @Test
    void normalSegmentsParsed() {
        String raw = """
                {"text":"你好。会议开始。","duration":9.5,"cost_sec":1.2,
                 "segments":[{"start":0.12,"end":3.45,"text":"你好。"},
                              {"start":3.5,"end":9.4,"text":"会议开始。"}]}
                """;
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(raw);
        assertEquals("你好。会议开始。", r.text());
        assertNotNull(r.segments());
        assertEquals(2, r.segments().size());
        assertEquals(0.12, r.segments().get(0).start(), 1e-9);
        assertEquals(9.4, r.segments().get(1).end(), 1e-9);
        assertEquals("会议开始。", r.segments().get(1).text());
    }

    /** 老响应只有三键（T4.35a 之前的服务）：segments=null（不是 []）。 */
    @Test
    void legacyResponseWithoutSegmentsKeyGivesNull() {
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(
                "{\"text\":\"旧响应\",\"duration\":9.5,\"cost_sec\":1.2}");
        assertEquals("旧响应", r.text());
        assertNull(r.segments(), "无 segments 键 → null（老语义），不是 []");
    }

    /** 空音频：有响应无内容 → segments=[]（与旧任务 null 是两回事，卡面逐字）。 */
    @Test
    void emptySegmentsArrayStaysEmpty() {
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(
                "{\"text\":\"\",\"duration\":0.0,\"cost_sec\":0.3,\"segments\":[]}");
        assertEquals("", r.text());
        assertNotNull(r.segments());
        assertEquals(0, r.segments().size());
    }

    /** 负数或乱序（end<start）不采信：跳过该元素，不抛。 */
    @Test
    void negativeOrOutOfOrderSegmentsDropped() {
        String raw = """
                {"text":"x","duration":10.0,"segments":[
                  {"start":-1.0,"end":2.0,"text":"负 start"},
                  {"start":5.0,"end":4.0,"text":"end<start"},
                  {"start":6.0,"end":7.0,"text":"正常段"}]}
                """;
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(raw);
        assertNotNull(r.segments());
        assertEquals(1, r.segments().size());
        assertEquals("正常段", r.segments().get(0).text());
    }

    /** 空 text：保留元素（strip 后为空串），不抛；首尾空白被去掉。 */
    @Test
    void blankTextSegmentKeptAndStripped() {
        String raw = """
                {"text":"  ","duration":5.0,"segments":[
                  {"start":0.0,"end":1.0,"text":"   "},
                  {"start":1.0,"end":2.0,"text":"  有内容  "}]}
                """;
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(raw);
        assertNotNull(r.segments());
        assertEquals("", r.segments().get(0).text());
        assertEquals("有内容", r.segments().get(1).text());
    }

    /** 非 JSON：text=null（调用方按 ASR_ERROR 处理），不抛、不 500。 */
    @Test
    void nonJsonBodyYieldsNullTextNotThrow() {
        assertNull(AsrResponseParser.parse("this is not json").text());
        assertNull(AsrResponseParser.parse("").text());
        assertNull(AsrResponseParser.parse(null).text());
        assertNull(AsrResponseParser.parse("   ").text());
        assertNull(AsrResponseParser.parse("{\"text\":").text());
    }

    /** 元素缺字段/非数值：不采信该元素，不抛。 */
    @Test
    void malformedSegmentItemsDropped() {
        String raw = """
                {"text":"x","segments":[
                  {"start":1.0,"text":"缺 end"},
                  {"end":2.0,"text":"缺 start"},
                  {"start":"abc","end":3.0,"text":"start 非数值"},
                  {"start":3.0,"end":4.0,"text":"完整"}]}
                """;
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(raw);
        assertEquals(1, r.segments().size());
        assertEquals("完整", r.segments().get(0).text());
    }

    /** segments 不是数组（脏数据）→ null。 */
    @Test
    void nonArraySegmentsGivesNull() {
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(
                "{\"text\":\"x\",\"segments\":\"oops\"}");
        assertEquals("x", r.text());
        assertNull(r.segments());
    }

    /** JSON 数值是 int 时（如 start:0）也正常解析为 double。 */
    @Test
    void integerNumbersAccepted() {
        AsrResponseParser.AsrResult r = AsrResponseParser.parse(
                "{\"text\":\"x\",\"segments\":[{\"start\":0,\"end\":1,\"text\":\"整数值\"}]}");
        assertEquals(1, r.segments().size());
        assertEquals(0.0, r.segments().get(0).start(), 1e-9);
        assertTrue(r.segments().get(0).end() == 1.0);
    }
}
