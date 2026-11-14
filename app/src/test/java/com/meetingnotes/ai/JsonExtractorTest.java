package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JsonExtractor} 边界用例（T3.4 验收③：每条断言注明"为什么该成功/该失败"）。
 */
class JsonExtractorTest {

    @Test
    void pureObjectIsReturnedAsIs() {
        // 纯 JSON：无需容错，抽取结果应与原文完全一致
        String json = "{\"summary\":\"周会结论\",\"keywords\":[\"发布\"],\"todos\":[]}";
        assertEquals(json, JsonExtractor.extract(json));
    }

    @Test
    void proseWrappedObjectIsExtracted() {
        // 前后有散文：从第一个 { 到匹配的 } —— 散文不算结构符，该成功
        String raw = "好的，以下是结构化结果：{\"summary\":\"结论\"} 以上供参考。";
        assertEquals("{\"summary\":\"结论\"}", JsonExtractor.extract(raw));
    }

    @Test
    void fencedJsonIsExtracted() {
        // ```json 围栏：先取围栏内容，围栏外的散文不干扰
        String raw = "分析如下\n```json\n{\"summary\":\"要点\",\"todos\":[]}\n```\n完毕";
        assertEquals("{\"summary\":\"要点\",\"todos\":[]}", JsonExtractor.extract(raw));
    }

    @Test
    void fenceWithoutLanguageTagIsExtracted() {
        // 围栏无 json 标记（裸 ```）：同样该成功
        String raw = "```\n{\"summary\":\"A\"}\n```";
        assertEquals("{\"summary\":\"A\"}", JsonExtractor.extract(raw));
    }

    @Test
    void unclosedFenceStillFallsBackToWholeText() {
        // 围栏残缺（只有开头 ```json、无闭合）：围栏匹配不到 → 落回全文扫描，该成功而非降级
        String raw = "```json\n{\"summary\":\"残缺围栏\"}";
        assertEquals("{\"summary\":\"残缺围栏\"}", JsonExtractor.extract(raw));
    }

    @Test
    void closingBraceInsideStringDoesNotCloseObject() {
        // 字符串字面量里含 } ：不是结构符，对象在真正的末尾 } 才闭合
        String raw = "{\"summary\":\"他说：}这个不对\",\"todos\":[]}";
        assertEquals(raw, JsonExtractor.extract(raw));
    }

    @Test
    void escapedQuoteInsideStringDoesNotEndString() {
        // 转义 \" ：字符串实际在最后一个 " 才结束，其后的 } 才是结构符
        String raw = "{\"summary\":\"引号\\\"在中间\"} 尾部散文";
        assertEquals("{\"summary\":\"引号\\\"在中间\"}", JsonExtractor.extract(raw));
    }

    @Test
    void openBraceInsideStringIsNotCounted() {
        // 字符串里含 { ：不增加深度，否则深度多 1 导致匹配外层错位
        String raw = "{\"summary\":\"集合{a,b}\",\"todos\":[]}extra";
        assertEquals("{\"summary\":\"集合{a,b}\",\"todos\":[]}", JsonExtractor.extract(raw));
    }

    @Test
    void nestedObjectsAndArraysAreBalanced() {
        // 嵌套 {}/[]：深度计数必须回到 0 才算闭合，输出须是完整外层对象
        String raw = "{\"summary\":\"S\",\"keywords\":[\"k1\",\"k2\"],\"todos\":[{\"action\":\"a\"}]}";
        assertEquals(raw, JsonExtractor.extract("前缀：" + raw + "：后缀"));
    }

    @Test
    void unclosedObjectReturnsNull() {
        // { 不闭合：深度回不到 0 → 抽不到 → 返回 null，走失败降级
        assertNull(JsonExtractor.extract("{\"summary\":\"没闭合\""));
        assertNull(JsonExtractor.extract("散文 {\"a\":1"));
    }

    @Test
    void nonObjectRootReturnsNull() {
        // 根不是对象（数组/数字/空串/null）：全文无 { → null，交给降级而不是硬解析
        assertNull(JsonExtractor.extract("[1,2,3]"));
        assertNull(JsonExtractor.extract("42"));
        assertNull(JsonExtractor.extract(""));
        assertNull(JsonExtractor.extract(null));
    }
}
