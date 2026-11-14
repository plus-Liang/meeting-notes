package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 摘要生成（T3.1 / D-068）：转写全文 → {summary, keywords}。
 *
 * <p>同步调用（任务 DONE 后紧接执行），但**任何失败都不阻塞任务**：无 ChatModel（未配 key）、
 * 调用异常/超时、JSON 解析失败 → WARN + summary/keywords 留空（保持 null，由 T3.3 三档降级接管），
 * 任务仍 DONE（转写成果不能因摘要丢失）。解析失败不重试；WARN 带模型原始输出截 200 字（不进响应体）。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final ObjectProvider<ChatModel> chatModel;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public SummaryService(ObjectProvider<ChatModel> chatModel, JdbcTemplate jdbc) {
        this.chatModel = chatModel;
        this.jdbc = jdbc;
    }

    /** 提示词（package-private 供单测断言约束完整）。 */
    static String buildPrompt(String transcript) {
        return """
                你是会议纪要助手。请阅读下面的转写全文，输出严格的 JSON（不要输出任何其它文字或代码块标记）：
                {"summary": "不超过 200 字的会议摘要", "keywords": ["关键词1", "关键词2"]}
                要求：summary 覆盖主要议题与结论；keywords 抽取 3~8 个关键名词/术语。
                严禁编造 transcript 里没有的人名、数字、日期与结论；不确定的内容不要写。

                转写全文：
                """ + transcript;
    }

    /**
     * @return true = 摘要已写入 task_result；false = 跳过或失败（任务不受影响，summary 保持 null）
     */
    public boolean summarize(long taskId, String transcript) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            log.warn("未配置 LLM（无 ChatModel bean），跳过摘要（任务仍 DONE）");
            return false;
        }
        if (transcript == null || transcript.isBlank()) {
            log.info("transcript 为空（D-048 无语音内容），跳过摘要");
            return false;
        }
        String raw;
        try {
            raw = model.call(buildPrompt(transcript));
        } catch (RuntimeException e) {
            log.warn("LLM 调用失败/超时，摘要留空（任务仍 DONE）：{}", e.getMessage());
            return false;
        }
        String summary;
        List<String> keywords;
        try {
            JsonNode node = mapper.readTree(raw);
            summary = node.path("summary").asText(null);
            keywords = mapper.convertValue(node.path("keywords"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            // 解析失败：不重试（D-068），WARN 带原始输出截 200 字便于排查格式漂移
            log.warn("LLM 输出不是合法 JSON，摘要留空；原始输出截 200 字：{}",
                    raw == null ? "null" : raw.substring(0, Math.min(200, raw.length())));
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("LLM 输出缺少 summary 字段，摘要留空；原始输出截 200 字：{}",
                    raw == null ? "null" : raw.substring(0, Math.min(200, raw.length())));
            return false;
        }
        jdbc.update("""
                UPDATE task_result SET summary = ?, keywords = ?::jsonb, ai_tier = 'CLOUD'
                WHERE task_id = ?
                """, summary, toJson(keywords), taskId);
        log.info("摘要已落库（{} 字，{} 个关键词）", summary.length(), keywords == null ? 0 : keywords.size());
        return true;
    }

    private String toJson(List<String> keywords) {
        try {
            return mapper.writeValueAsString(keywords == null ? List.of() : keywords);
        } catch (Exception e) {
            return "[]";
        }
    }
}
