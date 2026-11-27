package com.meetingnotes.worker;

import com.meetingnotes.asr.AsrClient;
import com.meetingnotes.asr.AsrResponseParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * T4.99/C5：转写步骤独立组件——拿任务行里的语言调 ASR，并在**这一处**把 ASR 异常包装成
 * {@link ProcessingException}("ASR_ERROR")。抽出的原因：TranscriptionWorker 已顶满 M-3 的 300 行
 * 上限，任何新增必须挪出去；同时把「用户可见失败原因」的脱敏收敛到单点（见下）。
 *
 * <p>脱敏纪律（T4.99/C6）：{@code dead_letter.reason} 是给用户看的，**不得**出现内部地址、
 * 容器名、异常类名或 {@code e.getMessage()} 原文——所以这里只给一句完整人话，异常链（cause）
 * 原样保留：worker 的 {@code log.error("处理异常", e)} 仍输出完整堆栈，排障能力不丢。
 */
@Component
public class TaskTranscriber {

    /** ASR 不可用时给用户的人话（逐字，Lead 验收 A6 会比对 dead_letter.reason）。 */
    static final String ASR_FAILURE_MESSAGE = "语音识别服务调用失败，请检查 ASR 服务是否正常（详见服务端日志）";

    private final AsrClient asrClient;

    public TaskTranscriber(AsrClient asrClient) {
        this.asrClient = asrClient;
    }

    /** 转写结果：text 拼接全文 + segments（可能为 null）。 */
    public record Result(String text, List<AsrResponseParser.Segment> segments) {
    }

    /**
     * 调 ASR 转写一个 16k 单声道 wav。
     *
     * @param language 任务级语言（worker 从 task.language 带出，''=NULL=部署级默认；auto=自动检测），
     *                 透传 {@link AsrClient#transcribe(Path, String)}
     * @throws ProcessingException code=ASR_ERROR（message 为脱敏人话，cause 保留原异常）
     */
    public Result transcribe(String language, Path wav) {
        try {
            AsrClient.Transcription result = asrClient.transcribe(wav, normalize(language));
            return new Result(result.text(), result.segments());
        } catch (RuntimeException e) {
            throw new ProcessingException("ASR_ERROR", ASR_FAILURE_MESSAGE, e);
        }
    }

    /** ''（COALESCE 占位）/空白 → null（不加 language 字段，走部署级默认）；其余原样透传。 */
    private static String normalize(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return language;
    }
}
