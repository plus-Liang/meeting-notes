package com.meetingnotes.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 转写 AI 加标点（T4.18 / D-109）：结构化成功后 worker 追加的**独立** LLM 调用（不并入结构化主调用——
 * 长转写会撞 max_tokens 导致整档降级）。仅 CLOUD 档可用时做；输出只加标点、不加不删不改字。
 *
 * <p>失败语义（D-068/D-075 同款）：关闭 / 空转写 / 超长 / CLOUD 不可用 / 超时异常 / 空 content /
 * {@code finish_reason=length} / **长度异常**（去标点后长度差异 > {@value #LENGTH_TOLERANCE_PERCENT}%）
 * → 返回 false，{@code task_result.transcript_punctuated} 留 null，**任务仍 DONE、ai_tier 不动**。
 * 日志只记耗时/字数/成功与否，不打印全文（隐私口径同 T3.6）。
 */
@Service
public class TranscriptPunctuator {

    private static final Logger log = LoggerFactory.getLogger(TranscriptPunctuator.class);

    /** 长度异常判定：输出去标点后与原文去标点后长度差异容忍度（%，卡片口径 >5% 视为异常）。 */
    static final double LENGTH_TOLERANCE_PERCENT = 5.0;

    private final ObjectProvider<ChatModel> cloudModel;
    private final JdbcTemplate jdbc;
    private final int maxChars;

    public TranscriptPunctuator(@Qualifier("openAiChatModel") ObjectProvider<ChatModel> cloudModel,
                                JdbcTemplate jdbc,
                                @Value("${app.llm.punctuate-max-chars:6000}") int maxChars) {
        this.cloudModel = cloudModel;
        this.jdbc = jdbc;
        this.maxChars = maxChars;
    }

    /**
     * 加标点并落库。
     *
     * @return true = 已写入 task_result.transcript_punctuated；false = 跳过或失败（留 null，不影响任务完成）
     */
    public boolean punctuateAndPersist(long taskId, String transcript) {
        if (maxChars <= 0) {
            log.info("标点关闭（LLM_PUNCTUATE_MAX_CHARS=0），跳过");
            return false;
        }
        if (transcript == null || transcript.isBlank()) {
            return false;
        }
        if (transcript.length() > maxChars) {
            log.info("transcript {} 字超过标点上限 {} 字，跳过", transcript.length(), maxChars);
            return false;
        }
        ChatModel model = cloudModel.getIfAvailable();
        if (model == null) {
            log.info("CLOUD 档不可用，跳过标点");
            return false;
        }
        long start = System.currentTimeMillis();
        String punctuated;
        try {
            ChatResponse response = model.call(new Prompt(buildPrompt(transcript)));
            if ("length".equalsIgnoreCase(finishReasonOf(response))) {
                log.warn("标点输出被 max_tokens 截断（usage={}），放弃标点", usageOf(response));
                return false;
            }
            punctuated = contentOf(response);
        } catch (RuntimeException e) {
            log.warn("标点调用失败（不影响任务完成）：{}", e.toString());
            return false;
        }
        if (punctuated == null || punctuated.isBlank()) {
            log.warn("标点返回空 content，放弃标点");
            return false;
        }
        String trimmed = punctuated.trim();
        if (!lengthPlausible(transcript, trimmed)) {
            log.warn("标点输出长度异常（原文 {} 字/输出去标点 {} 字差异超 {}%），放弃标点，不落库",
                    transcript.length(), strippedLength(trimmed), LENGTH_TOLERANCE_PERCENT);
            return false;
        }
        jdbc.update("UPDATE task_result SET transcript_punctuated = ? WHERE task_id = ?", trimmed, taskId);
        log.info("标点完成并落库：{} 字 → {} 字，耗时 {}ms",
                transcript.length(), trimmed.length(), System.currentTimeMillis() - start);
        return true;
    }

    static String buildPrompt(String transcript) {
        return """
                你是转写文本标点助手。请给下面的转写全文添加标点符号（句号/问号/感叹号/逗号/顿号等）。
                只加标点：不得改写用词、不得删句、不得增加内容、不得翻译、不得整理格式。
                只输出加标点后的全文，不要输出任何解释或代码块标记。

                转写全文：
                """ + transcript;
    }

    /** 长度合理性：比较去标点（只留字母/数字/汉字）后的长度，差异 ≤ {@value #LENGTH_TOLERANCE_PERCENT}% 视为合理。 */
    static boolean lengthPlausible(String original, String punctuated) {
        int a = strippedLength(original);
        int b = strippedLength(punctuated);
        if (a == 0) {
            return b == 0;
        }
        return Math.abs(b - a) <= a * LENGTH_TOLERANCE_PERCENT / 100.0;
    }

    /** 去标点长度：只统计字母/数字/汉字（标点、空白、符号一律不算）。 */
    static int strippedLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static String contentOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return "unknown";
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    /** 诊断用 usage（口径同 StructuringService⑥，标点失败时帮助定位是不是 reasoning 吃光预算）。 */
    private static String usageOf(ChatResponse response) {
        try {
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            return usage == null ? "usage=null"
                    : "prompt=" + usage.getPromptTokens() + ", completion=" + usage.getCompletionTokens();
        } catch (RuntimeException e) {
            return "usage=unavailable";
        }
    }
}
