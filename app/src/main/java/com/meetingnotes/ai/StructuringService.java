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
 * 结构化编排（T3.3）：摘要 + 关键词 + 待办 + 要点（T4.17）**一次 LLM 调用**（F-7），三档**级联降级**（D-075）。
 * 首选档由 {@link AiStructuringRouter} 判定 → 异常/空 content/length/解析失败 → 降一档再试（CLOUD → LOCAL → EXTRACTIVE）；
 * {@code ai_tier} 落实际产出那一档（D-020）。EXTRACTIVE 零依赖兜底：transcript 非空必有产出；空 transcript 跳过（D-048）。
 * 不重试同一档（D-068/D-071）；失败诊断写 WARN；阈值由代码层再截断（⑦ 预算兜底）。
 * T4.26（F-17）：{@code structure(taskId, transcript, explicitTemplate)} 重跑入口——显式模板由调用方校验后传入，
 * persist 把 template 一并写（worker 路径两参重载不变，template 仍读落库值并原样写回）。
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
    private final GlossaryTermSource termSource;
    private final JdbcTemplate jdbc;
    private final boolean extractiveEnabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuringService(@Qualifier("openAiChatModel") ObjectProvider<ChatModel> cloudModel,
                              @Qualifier("localOllamaChatModel") ObjectProvider<ChatModel> localModel,
                              AiStructuringRouter router,
                              TodoService todoService,
                              GlossaryTermSource termSource,
                              JdbcTemplate jdbc,
                              @Value("${app.llm.fallback-extractive:true}") boolean extractiveEnabled) {
        this.cloudModel = cloudModel;
        this.localModel = localModel;
        this.router = router;
        this.todoService = todoService;
        this.termSource = termSource;
        this.jdbc = jdbc;
        this.extractiveEnabled = extractiveEnabled;
    }

    /** @return true = 结果已写入 task_result；false = 空 transcript 跳过或降级链耗尽（字段一起留空，任务仍 DONE） */
    public boolean structure(long taskId, String transcript) {
        return structure(taskId, transcript, null);
    }

    /** 重跑入口（T4.26/F-17）：explicitTemplate 非空时直接用（调用方已 validateExplicit），否则读落库模板（worker 路径不变）。 */
    public boolean structure(long taskId, String transcript, String explicitTemplate) {
        if (transcript == null || transcript.isBlank()) {
            log.info("transcript 为空（D-048 无语音内容），跳过结构化，ai_tier 留 null");
            return false;
        }
        String template = explicitTemplate != null ? explicitTemplate : templateOf(taskId);
        log.info("结构化模板：{}", template);
        GlossaryTermSource.GlossaryInjection injection = termSource.inject();
        injection = injection == null || injection.termCount() <= 0
                ? GlossaryTermSource.GlossaryInjection.EMPTY : injection;
        log.info("术语注入：{} 条（≤20）/ 长度 {} 字", injection.termCount(), injection.text().length());
        AiTier tier = router.preferredTier();
        log.info("结构化首选档：{}", tier);
        while (tier != null) {
            StructuringResult result = attempt(tier, transcript, injection.text(), template);
            if (result != null) {
                persist(taskId, result, template);
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

    /** 单档尝试：任何失败都返回 null（由上层降级），异常不外抛。EXTRACTIVE 不做模板分化（F-18，只落 template）。 */
    private StructuringResult attempt(AiTier tier, String transcript, String glossaryText, String template) {
        try {
            return switch (tier) {
                case CLOUD -> fromModel(cloudModel, AiTier.CLOUD, transcript, glossaryText, template);
                case LOCAL -> fromModel(localModel, AiTier.LOCAL, transcript, glossaryText, template);
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
        result = withGroundedTodos(result, transcript);
        // F-15（D-089）：依据校验在前（对原文），词表确定性替换在后（right_text 不在原文，先替换会被误杀）
        return ExtractiveGlossaryApplier.apply(result, termSource.terms());
    }

    /** 云档/本地档：一次调用 → 诊断 → 解析 → 依据校验。 */
    private StructuringResult fromModel(ObjectProvider<ChatModel> provider, AiTier tier,
                                        String transcript, String glossaryText, String template) {
        ChatModel model = provider.getIfAvailable();
        if (model == null) {
            log.warn("{} 档没有可用的 ChatModel（未配置该档），跳过", tier);
            return null;
        }
        ChatResponse response = model.call(new Prompt(StructuringPrompt.build(transcript, glossaryText, template)));
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

    /**
     * 合并 JSON 解析（同一个 DTO，三档同构）：summary 必填，keywords/todos 容错但形状必须合法；
     * keypoints 容错且**不参与降级**（T4.17/D-109：缺失或形状不合法 → null，其余字段照常落库）。
     * T3.4 ④：先经 {@link JsonExtractor} 抽取（散文包裹 / ```json 围栏），抽不到才按解析失败处理——
     * 不放宽提示词硬约束，失败语义（其余字段一起留空 + 降级）不变。
     */
    StructuringResult parse(String raw, AiTier tier) {
        JsonNode root;
        try {
            String candidate = JsonExtractor.extract(raw);
            root = candidate == null ? null : mapper.readTree(candidate);
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
        return new StructuringResult(truncateSummary(summary.trim()),
                keywords(root.path("keywords")), todoService.parse(root),
                Keypoints.parse(root.path("keypoints")), tier);
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

    /** ⑨ 三档统一：owner/deadline 无依据置 null，action 无依据整条丢弃（D-076）；keypoints 原样透传。 */
    private static StructuringResult withGroundedTodos(StructuringResult result, String transcript) {
        return new StructuringResult(result.summary(), result.keywords(),
                TodoGrounding.validate(result.todos(), transcript), result.keypoints(), result.aiTier());
    }

    /** 读提交时落库的模板（T3.9）：COALESCE 兜 NULL（旧任务）→ meeting；查不到行同样按 meeting。 */
    private String templateOf(long taskId) {
        List<String> rows = jdbc.query(
                "SELECT COALESCE(template, 'meeting') FROM task_result WHERE task_id = ?",
                (rs, i) -> rs.getString(1), taskId);
        if (rows == null || rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return TemplatePrompts.DEFAULT_TEMPLATE;
        }
        return rows.get(0);
    }

    private void persist(long taskId, StructuringResult result, String template) {
        jdbc.update("""
                UPDATE task_result SET summary = ?, keywords = ?::jsonb, todos = ?::jsonb, keypoints = ?::jsonb,
                       ai_tier = ?, template = ?
                WHERE task_id = ?
                """, result.summary(), toJson(result.keywords()), toJson(result.todos()),
                result.keypoints() == null ? null : toJson(result.keypoints()),
                result.aiTier().name(), template, taskId);
        log.info("结构化已落库（ai_tier={}，template={}，summary {} 字，keywords {} 个，todos {} 条，keypoints {}）",
                result.aiTier().name(), template, result.summary().length(), result.keywords().size(),
                result.todos().size(), result.keypoints() == null ? "未生成" : result.keypoints().size() + " 条");
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

    /** 摘要句边界截断（T4.16b/D-107）：≤200 原样；超长在 [0,200) 内找最后一个句末标点截到它含标点（治「…证据链。未明确」末句腰斩）；找不到退回 200 硬截。诊断日志 truncate 不动。 */
    static String truncateSummary(String text) {
        if (text == null || text.length() <= MAX_SUMMARY_CHARS) {
            return text;
        }
        int end = lastSentenceEnd(text, MAX_SUMMARY_CHARS);
        return end > 0 ? text.substring(0, end) : text.substring(0, MAX_SUMMARY_CHARS);
    }

    /** [0,limit) 内最后一个句末标点的下一个位置（含标点）；找不到 -1。 */
    private static int lastSentenceEnd(String text, int limit) {
        for (int i = limit - 1; i >= 0; i--) {
            if ("。！？!?".indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
