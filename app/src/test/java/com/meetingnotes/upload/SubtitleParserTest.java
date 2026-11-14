package com.meetingnotes.upload;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.7/D-084：字幕解析口径——srt 容忍序号/时间轴/空行/多行 cue/\r\n；ass 只取 [Events] 的 Text:
 * （前 9 个逗号 split-limit）并剥样式标签与 \N；编码 UTF-8（去 BOM）→ GBK 回退 → 都失败 400；
 * 同一输入两次解析逐字节一致。
 */
class SubtitleParserTest {

    private static final String SRT = """
            1
            00:00:01,000 --> 00:00:03,500
            大家好，会议开始。
            第二行内容。

            2
            00:00:04,000 --> 00:00:06,000
            结束语
            """;

    private static final String ASS = """
            [Script Info]
            Title: test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,第一句，带逗号。{\\an8}标签要剥掉
            Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,第二句\\N换行
            """;

    @Test
    void srtStripsIndexesTimestampsAndBlankLines() {
        String text = SubtitleParser.parseSrt(SRT);

        assertEquals("大家好，会议开始。\n第二行内容。\n结束语", text);
    }

    @Test
    void srtHandlesCrLf() {
        String text = SubtitleParser.parseSrt("1\r\n00:00:01,000 --> 00:00:02,000\r\n你好\r\n");

        assertEquals("你好", text);
    }

    @Test
    void assTakesEventsTextAndStripsStyleTags() {
        String text = SubtitleParser.parseAss(ASS);

        assertEquals("第一句，带逗号。标签要剥掉\n第二句\n换行", text);
    }

    @Test
    void parsingIsDeterministic() {
        assertEquals(SubtitleParser.parseSrt(SRT), SubtitleParser.parseSrt(SRT));
        assertEquals(SubtitleParser.parseAss(ASS), SubtitleParser.parseAss(ASS));
    }

    @Test
    void utf8BomIsStripped() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "1\n00:00:01,000 --> 00:00:02,000\n带 BOM 的字幕\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        assertEquals("带 BOM 的字幕", SubtitleParser.parseSrt(SubtitleParser.decode(bytes)));
    }

    @Test
    void gbkFallbackDecodesWhenNotUtf8() {
        // "会议纪要" 的 GBK 字节不是合法 UTF-8 → 必须回退 GBK 解码成功
        byte[] gbk = "会议纪要".getBytes(Charset.forName("GBK"));

        assertEquals("会议纪要", SubtitleParser.decode(gbk));
    }

    @Test
    void undecodableBytesRejectedWith400() {
        // 0x80 单字节在 UTF-8 与 GBK 里都是非法起始/截断序列 → 400
        ApiException e = assertThrows(ApiException.class, () -> SubtitleParser.decode(new byte[]{(byte) 0x80}));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("编码"));
    }

    @Test
    void emptyContentRejectedWith400() {
        assertEquals(400, assertThrows(ApiException.class, () -> SubtitleParser.decode(new byte[0])).getStatus());
    }
}
