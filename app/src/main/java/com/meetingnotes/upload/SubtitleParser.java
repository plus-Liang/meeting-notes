package com.meetingnotes.upload;

import com.meetingnotes.common.ApiException;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 字幕解析（T3.7，独立小类不进 controller）：srt / ass → 纯文本 transcript。
 *
 * <p>口径（D-084 裁决 3/7，固定免扯皮）：
 * <ul>
 *   <li>**编码**：UTF-8 优先（strict 解码 + 去 BOM），失败回退 GBK（同样 strict），两者都失败 →
 *       400"字幕文件编码无法识别"——不许用静默 replace 吞坏字符（spec US-5 注）；</li>
 *   <li>**srt**：容忍序号行/时间轴行（含 {@code -->}）/空行/多行 cue/{@code \r\n}，文本行按原序合并；</li>
 *   <li>**ass**：只取 {@code [Events]} 段的 {@code Text:} 行，按前 9 个逗号 split-limit 取最后一段
 *       （Format 前 9 列是 Layer…Effect），剥 {@code {...}} 样式标签（{\an8} 之类）、
 *       {@code \N}/{@code \n} → 换行；</li>
 *   <li>**确定性**：同一输入两次解析逐字节一致（纯函数，单测保证）。</li>
 * </ul>
 * 解析不出任何文本由调用方判空 → 400"字幕文件没有可识别文本"。
 */
public final class SubtitleParser {

    /** 字幕文件大小上限：5 MB（超限 413，别让大 multipart 进解析器）。 */
    public static final long MAX_SUBTITLE_BYTES = 5L * 1024 * 1024;

    private SubtitleParser() {
    }

    /** UTF-8（去 BOM，strict）→ 失败回退 GBK（strict）→ 两者都失败 400。 */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(400, "INVALID_PARAM", "字幕文件内容为空");
        }
        int offset = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException ignored) {
            // 不是合法 UTF-8 → 按 spec 回退 GBK（同样 strict，解码不了就报错，不静默吞）
        }
        try {
            return Charset.forName("GBK").newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(400, "INVALID_PARAM", "字幕文件编码无法识别");
        }
    }

    /** srt → 文本（去序号/时间轴/空行，多行 cue 按行保留）。 */
    public static String parseSrt(String content) {
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.matches("\\d+") || line.contains("-->")) {
                continue;
            }
            lines.add(line);
        }
        return String.join("\n", lines).trim();
    }

    /** ass → 文本（[Events] 段 Dialogue 行的第 10 段（前 9 个逗号 split-limit），剥样式标签与 \N）。 */
    public static String parseAss(String content) {
        boolean inEvents = false;
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inEvents = line.equalsIgnoreCase("[Events]");
                continue;
            }
            if (!inEvents || !line.startsWith("Dialogue:")) {
                continue;
            }
            String fields = line.substring("Dialogue:".length());
            String[] parts = fields.split(",", 10); // 前 9 列是 Layer…Effect，第 10 段起是台词（台词自身可能含逗号）
            if (parts.length < 10) {
                continue;
            }
            String text = parts[9]
                    .replaceAll("\\{[^}]*}", "")
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .trim();
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }
        return String.join("\n", lines).trim();
    }
}
