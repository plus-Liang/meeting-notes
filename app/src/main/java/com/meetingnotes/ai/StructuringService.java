package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化编排（T3.3）：摘要 + 关键词 + 待办 **一次 LLM 调用**（F-7），三档**级联降级**（D-075）。
 *
 * <p>流程：首选档由 {@link AiStructuringRouter} 按 plan §5.1 判定 → 该档
 * 调用异常 / 空 content / {@code finish_reason=length} / JSON 解析失败 → **降一档再试**
 * （CLOUD → LOCAL → EXTRACTIVE）；{@code ai_tier} 落**实际产出结果的那一档**（D-020 诚实标注）。
 * EXTRACTIVE 零依赖兜底：transcript 非空就必定有产出；空 transcript 跳过结构化、{@code ai_tier} 留 null（D-048）。
 *
 * <p>不重试同一档（D-068/D-071）：空 content 重试还是空；"第二次机会"是换方法（降级）。
 * 同一档的失败诊断写 WARN：{@code finish_reason} + usage（含 reasoning_tokens）+ 原始输出截 200 字。
 * 阈值（summary 200 字 / keywords 10 / todos 20）在解析后由**代码层**再截断一次（⑦ 体积预算兜底）。
 */
@Service
public class StructuringService {

    private static final Logger log = LoggerFactory.getLogger(StructuringService.class);

    static final int MAX_SUMMARY_CHARS = 200;
    static final int MAX_KEYWORDS = 10;
    private static final int RAW_TRACE_CHARS = 200;

    private final ObjectProvider<ChatModel> cloudModel;
    private final ObjectProvider<ChatModel> localModel;
    private final AiStructuringRouter router;
    private final TodoService todoService;
    private final JdbcTemplate jdbc;
    private final boolean extractiveEnabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuringService(@Qualifier("openAiChatModel") ObjectProvider<ChatModel> cloudModel,
                              @Qualifier("localOllamaChatModel") ObjectProvider<ChatModel> localModel,
                              AiStructuringRouter router,
                              TodoService todoService,
                              JdbcTemplate jdbc,
                              @Value("${app.llm.fallback-extractive:true}") boolean extractiveEnabled) {
        this.cloudModel = cloudModel;
        this.localModel = localModel;
        this.router = router;
        this.todoService = todoService;
        this.jdbc = jdbc;
        this.extractiveEnabled = extractiveEnabled;
    }

    /**
     * @return true = 结构化结果（summary/keywords/todos/ai_tier）已写入 task_result；
     *         false = 空 transcript 跳过，或降级链耗尽（三个字段一起留空，任务仍 DONE）
     */
    public boolean structure(long taskId, String transcript) {
        if (transcript == null || transcript.isBlank()) {
            log.info("transcript 为空（D-048 无语音内容），跳过结构化，ai_tier 留 null");
            return false;
        }
        AiTier tier = router.preferredTier();
        log.info("结构化首选档：{}", tier);
        while (tier != null) {
            StructuringResult result = attempt(tier, transcript);
            if (result != null) {
                persist(taskId, result);
                return true;
            }
            AiTier next = router.nextTier(tier);
            if (next != null) {
                log.warn("{} 档未产出可用结果，降级到 {} 档重试（换方法，不重复同一调用）", tier, next);
            }
            tier = next;
        }
        log.warn("降级链耗尽（transcript 非空），summary/keywords/todos/ai_tier 一起留空，任务仍 DONE");
        return false;
    }

    /** 单档尝试：任何失败都返回 null（由上层降级），异常不外抛。 */
    private StructuringResult attempt(AiTier tier, String transcript) {
        try {
            return switch (tier) {
                case CLOUD -> fromModel(cloudModel, AiTier.CLOUD, transcript);
                case LOCAL -> fromModel(localModel, AiTier.LOCAL, transcript);
                case EXTRACTIVE -> extractive(transcript);
            };
        } catch (RuntimeException e) {
            log.warn("{} 档结构化异常（任务仍 DONE）：{}", tier, e.toString());
            return null;
        }
    }

    private StructuringResult extractive(String transcript) {
        if (!extractiveEnabled) {
            log.warn("第三档抽取式兜底被关闭（app.llm.fallback-extractive=false），跳过");
            return null;
        }
        StructuringResult result = ExtractiveStructurer.extract(transcript);
        return withGroundedTodos(result, transcript);
    }

    /** 云档/本地档：一次调用 → 诊断 → 解析 → 依据校验。 */
    private StructuringResult fromModel(ObjectProvider<ChatModel> provider, AiTier tier, String transcript) {
        ChatModel model = provider.getIfAvailable();
        if (model == null) {
            log.warn("{} 档没有可用的 ChatModel（未配置该档），跳过", tier);
            return null;
        }
        ChatResponse response = model.call(new Prompt(StructuringPrompt.build(transcript)));
        String finishReason = finishReasonOf(response);
        String content = contentOf(response);
        if ("length".equalsIgnoreCase(finishReason)) {
            // ⑦ 体积预算被 reasoning 吃光：该档结果必然残缺 → 直接降档，不做无限重试
            log.warn("{} 档输出被 max_tokens 截断（finish_reason=length, usage={}），该档不可用，降级",
                    tier, usageOf(response));
            return null;
        }
        if (content == null || content.isBlank()) {
            log.warn("{} 档返回空 content（finish_reason={}, usage={}），降级", tier, finishReason, usageOf(response));
            return null;
        }
        StructuringResult parsed;
        try {
            parsed = parse(content, tier);
        } catch (RuntimeException e) {
            log.warn("{} 档输出不是合法结构化 JSON（{}），三字段一起留空并降级；原始输出截 200 字：{}",
                    tier, e.getMessage(), abbreviate(content));
            return null;
        }
        return withGroundedTodos(parsed, transcript);
    }

    /** 合并 JSON 解析（同一个 DTO，三档同构）：summary 必填，keywords/todos 容错但形状必须合法。 */
    StructuringResult parse(String raw, AiTier tier) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("输出不是 JSON 对象");
        }
        String summary = root.path("summary").asText(null);
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("缺少 summary 字段");
        }
        return new StructuringResult(truncate(summary.trim(), MAX_SUMMARY_CHARS),
                keywords(root.path("keywords")), todoService.parse(root), tier);
    }

    private static List<String> keywords(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("keywords 不是数组");
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode item : node) {
            String keyword = item.asText(null);
            if (keyword != null && !keyword.isBlank() && keywords.size() < MAX_KEYWORDS) {
                keywords.add(keyword.trim());
            }
        }
        return keywords;
    }

    /** ⑨ 三档统一：owner/deadline 无依据置 null，action 无依据整条丢弃（D-076）。 */
    private static StructuringResult withGroundedTodos(StructuringResult result, String transcript) {
        return new StructuringResult(result.summary(), result.keywords(),
                TodoGrounding.validate(result.todos(), transcript), result.aiTier());
    }

    private void persist(long taskId, StructuringResult result) {
        jdbc.update("""
                UPDATE task_result SET summary = ?, keywords = ?::jsonb, todos = ?::jsonb, ai_tier = ?
                WHERE task_id = ?
                """, result.summary(), toJson(result.keywords()), toJson(result.todos()),
                result.aiTier().name(), taskId);
        log.info("结构化已落库（ai_tier={}，summary {} 字，keywords {} 个，todos {} 条）",
                result.aiTier().name(), result.summary().length(),
                result.keywords().size(), result.todos().size());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
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

    /** 诊断用 usage（⑥）：prompt/completion/reasoning tokens——reasoning 吃光 max_tokens 时一眼能看出来（K-29）。 */
    static String usageOf(ChatResponse response) {
        try {
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage == null) {
                return "usage=null";
            }
            return "prompt=" + usage.getPromptTokens()
                    + ", completion=" + usage.getCompletionTokens()
                    + ", reasoning=" + reasoningTokens(usage);
        } catch (RuntimeException e) {
            return "usage=unavailable";
        }
    }

    private static Integer reasoningTokens(Usage usage) {
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage
                && nativeUsage.completionTokenDetails() != null) {
            return nativeUsage.completionTokenDetails().reasoningTokens();
        }
        return null;
    }

    private static String abbreviate(String text) {
        return text == null ? "null" : truncate(text, RAW_TRACE_CHARS);
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
