package com.meetingnotes.ai;

/**
 * 三档 AI 等级（D-020 / D-075）。
 *
 * <p>语义是**级联降级**而不是"静态选一档"：首选档调用失败 → 依次降一档（CLOUD → LOCAL → EXTRACTIVE），
 * `ai_tier` 落**实际产出该结果的那一档**（D-020 诚实标注：抽取式不许当成云摘要对外宣传）。
 */
public enum AiTier {

    /** 第一档：云 LLM（OpenAI 兼容协议，需 LLM_API_KEY/LLM_BASE_URL/LLM_MODEL 且非离线模式）。 */
    CLOUD,

    /** 第二档：本地 Ollama（OLLAMA_ENABLED + 模型名 + 探活成功）。 */
    LOCAL,

    /** 第三档：抽取式兜底（零依赖，transcript 非空就必定有产出）。 */
    EXTRACTIVE
}
