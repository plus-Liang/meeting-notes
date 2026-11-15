package com.meetingnotes.ai;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首选档判定 + 级联顺序（plan §5.1 / D-075 / D-077）：**tier 判定集中在这一个类**，service 里不写分散的 if/else。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>有 {@code LLM_API_KEY + LLM_BASE_URL + LLM_MODEL}（= {@code app.llm.enabled}）且 {@code OFFLINE_MODE=false} → CLOUD；</li>
 *   <li>{@code OLLAMA_ENABLED=true} + {@code OLLAMA_MODEL} 非空 + 探活成功（≤2s）→ LOCAL；</li>
 *   <li>其它 → EXTRACTIVE。</li>
 * </ol>
 * <b>D-077：{@code OFFLINE_MODE=true} 是云档硬门禁</b>——三件套齐也不许出站，同时必须 WARN 一次
 * （reason=offline_mode），否则用户只看到"有 key 却是 EXTRACTIVE"无法自查。
 *
 * <p>级联：{@code nextTier} 给出"降一档"的顺序 CLOUD → LOCAL → EXTRACTIVE → 无（null）。
 * 探活在**启动期**算一次并缓存（每任务不再付 2s）；失败只 WARN，不影响启动（K-04）。
 */
@Component
public class AiStructuringRouter {

    private static final Logger log = LoggerFactory.getLogger(AiStructuringRouter.class);

    private final boolean cloudEnabled;
    private final boolean offlineMode;
    private final boolean ollamaEnabled;
    private final boolean ollamaModelConfigured;
    private final OllamaProbe probe;
    private final AtomicBoolean offlineWarned = new AtomicBoolean();

    /** 探活结果缓存：null = 还没探过（懒探活，单测可直接触发）。 */
    private volatile Boolean ollamaReachable;

    public AiStructuringRouter(@Value("${app.llm.enabled:false}") boolean cloudEnabled,
                               @Value("${app.offline-mode:true}") boolean offlineMode,
                               @Value("${app.llm.ollama.enabled:false}") boolean ollamaEnabled,
                               @Value("${app.llm.ollama.model:}") String ollamaModel,
                               OllamaProbe probe) {
        this.cloudEnabled = cloudEnabled;
        this.offlineMode = offlineMode;
        this.ollamaEnabled = ollamaEnabled;
        this.ollamaModelConfigured = StringUtils.hasText(ollamaModel);
        this.probe = probe;
    }

    /** 启动期探活一次（≤2s）并打印档位判定；未启用本地档就完全不探（不付出启动延迟）。 */
    @PostConstruct
    void probeOnStartup() {
        boolean local = ollamaReady();
        log.info("结构化首选档判定：云配置 {} / 离线模式 {} / 本地档 {} → 首选 {}",
                cloudEnabled, offlineMode, local, preferredTier());
    }

    /** 首选档（plan §5.1 / D-077）。离线模式（禁一切出站）下不走云档，但本地档仍可用。 */
    public AiTier preferredTier() {
        if (cloudEnabled && !offlineMode) {
            return AiTier.CLOUD;
        }
        if (cloudEnabled && offlineWarned.compareAndSet(false, true)) {
            // D-077：硬门禁必须可诊断（spec §134 场景②：离线模式配了云 LLM → 不发起外连并告警）
            log.warn("云 LLM 配置齐全但被离线模式屏蔽（reason=offline_mode）：OFFLINE_MODE=true 禁一切出站，"
                    + "本题不判 CLOUD；要云质量请把 .env 的 OFFLINE_MODE 改为 false 后重启");
        }
        if (ollamaReady()) {
            return AiTier.LOCAL;
        }
        return AiTier.EXTRACTIVE;
    }

    /** 级联：降一档。EXTRACTIVE 已是最后一档 → null（降级链耗尽）。 */
    public AiTier nextTier(AiTier tier) {
        return switch (tier) {
            case CLOUD -> AiTier.LOCAL;
            case LOCAL -> AiTier.EXTRACTIVE;
            case EXTRACTIVE -> null;
        };
    }

    /** 本地档是否可用（启用 + 配了模型名 + 探活成功）；结果缓存，失败也不再重复探。 */
    boolean ollamaReady() {
        if (!ollamaEnabled || !ollamaModelConfigured) {
            return false;
        }
        Boolean cached = ollamaReachable;
        if (cached == null) {
            cached = probe.reachable();
            ollamaReachable = cached;
        }
        return cached;
    }

    boolean isOfflineMode() {
        return offlineMode;
    }

    /** 设置页展示（T4.1）：preferred 与 {@link #preferredTier()} 同一判定来源（单一口径，禁止复制逻辑）。 */
    public record TierView(String preferred, boolean cloudConfigured, boolean offlineMode, boolean localReady) {
    }

    public TierView tierView() {
        return new TierView(preferredTier().name(), cloudEnabled, offlineMode, ollamaReady());
    }
}
