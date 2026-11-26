package com.meetingnotes.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Webhook 投递（T4.6 / US-10，契约 = 接入指南.md §四，不自创字段）：任务终态（DONE→task.done / DEAD→task.failed）异步推送；
 * URL 取 task.callback_url 优先，否则全局 app.webhook.url；WEBHOOK_SECRET 为空不投递（安全默认，两条入口都挡，见 {@link #scanDue} F-22）；
 * OFFLINE_MODE=true 不投递（D-077）。worker 只做一次 {@link #onTerminal}（毫秒级记账），HTTP 投递在独立单线程执行——不阻塞 worker、不改 DONE 时序（D-069）。
 * 每次尝试先自增 attempts 再投（原子认领），失败按 WEBHOOK_MAX_RETRIES 指数退避 1s/10s/60s，终局 next_retry 置空留表可观测；
 * delivery_id 由行 id 派生确定性 UUID（重试同键去重）；签名 = HMAC-SHA256(secret, 原始请求体字节)。
 * 不做：手动重投 UI（P2）、secret 轮换（P2）、签名时间窗/重放防护（P3）。
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    /** 指数退避（秒）：第 1/2/3 次失败后分别等 1s/10s/60s（接入指南 §4.4 固定档）。 */
    static final long[] BACKOFF_SECONDS = {1, 10, 60};

    /** T4.83/P-33：目标地址被 SSRF 防护拒绝的终局状态（last_status 是 INT，V1 已建表，不新迁移）。
     *  取 -1 避开合法 HTTP 状态码区间；终局 = next_retry 置空，不把一次内网探测放大成 4 次重试。 */
    static final int STATUS_URL_BLOCKED = -1;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String globalUrl;
    private final String secret;
    private final int maxRetries;
    private final String baseUrl;
    private final boolean offlineMode;
    private final WebhookHttpSender sender;
    /** 单线程投递器：worker 终态回调只入队，HTTP 超时/重试不占 worker 线程。 */
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webhook-dispatcher");
        t.setDaemon(true);
        return t;
    });
    /** F-22：空 secret 的 WARN 只发一次——配置缺失是稳态，扫描每秒一轮回没有信息量。 */
    private final AtomicBoolean emptySecretWarned = new AtomicBoolean(false);

    public WebhookDeliveryService(JdbcTemplate jdbc,
                                  ObjectMapper mapper,
                                  @Value("${app.webhook.url}") String globalUrl,
                                  @Value("${app.webhook.secret}") String secret,
                                  @Value("${app.webhook.max-retries}") int maxRetries,
                                  @Value("${app.base-url}") String baseUrl,
                                  @Value("${app.offline-mode}") boolean offlineMode,
                                  WebhookHttpSender sender) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.globalUrl = globalUrl == null ? "" : globalUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.maxRetries = maxRetries;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.offlineMode = offlineMode;
        this.sender = sender;
    }

    /** 任务进入终态时的唯一入口（worker 调用，绝不抛异常拖垮任务流程）。 */
    public void onTerminal(long taskId, String status) {
        try {
            // T4.96/C7-6（D-245）：回收站任务不投递；恢复后再进终态会照常走到这里
            if (isTrashed(taskId)) {
                log.atInfo().addKeyValue("taskId", taskId).addKeyValue("reason", "TRASHED")
                        .log("任务 {} 终态 {}：任务在回收站，跳过 Webhook 投递", taskId, status);
                return;
            }
            if (offlineMode) {
                log.info("任务 {} 进入终态 {}：离线模式跳过 Webhook（OFFLINE_MODE=true 禁一切出站，D-077）", taskId, status);
                return;
            }
            if (secret.isEmpty()) {
                log.debug("任务 {} 进入终态 {}：WEBHOOK_SECRET 为空，不投递 Webhook", taskId, status);
                return;
            }
            String event = eventFor(status);
            String url = resolveUrl(taskId);
            if (url == null) {
                log.info("任务 {} 终态 {}：callback_url 与 WEBHOOK_URL 均为空，不投递", taskId, event);
                return;
            }
            Long id = jdbc.query("""
                    INSERT INTO webhook_delivery (task_id, url, event, attempts, next_retry)
                    VALUES (?, ?, ?, 0, now()) RETURNING id
                    """, rs -> rs.next() ? rs.getLong(1) : null, taskId, url, event);
            if (id == null) {
                log.error("任务 {} 终态 {}：webhook_delivery 记账失败（跳过投递）", taskId, event);
                return;
            }
            dispatcher.submit(() -> attempt(id));
        } catch (RuntimeException e) {
            log.warn("任务 {} 终态 {}：Webhook 调度异常（不影响任务结果）", taskId, status, e);
        }
    }
    /**
     * 重试扫描：到期行交投递器（attempt 内先认领，重复扫描不会双投）。
     * F-22（T4.12 / K-49：调度器必须重放"入队前置条件"）：本扫描与 {@link #onTerminal} 是两条入口，空 secret 必须都挡，
     * 否则遗留/手工到期行被空密钥签名异常按 1s/10s/60s 空烧 4 次到终局，且 {@code HMAC-SHA256 不可用} 文案误导排障（真因是配置缺失）。
     * 空 secret：不投递、不消耗 attempts，next_retry 保持原样（补配 secret 重启后自动续投，同 T4.8 重启续跑语义）。
     */
    @Scheduled(fixedDelay = 1000)
    public void scanDue() {
        if (offlineMode) {
            return;
        }
        try {
            // T4.96/C7-6（F-22 同理：重试扫描是第二条投递入口，回收站行不投）
            List<Long> due = jdbc.queryForList("""
                    SELECT w.id FROM webhook_delivery w
                    JOIN task t ON t.id = w.task_id AND t.deleted_at IS NULL
                    WHERE w.next_retry IS NOT NULL AND w.next_retry <= now()
                    """, Long.class);
            if (due.isEmpty()) {
                return;
            }
            if (secret.isEmpty()) {
                if (emptySecretWarned.compareAndSet(false, true)) {
                    log.warn("Webhook 重试扫描发现 {} 行到期投递，但 WEBHOOK_SECRET 未配置（配置缺失）："
                            + "跳过投递、不消耗重试次数，补配 secret 并重启后自动续投；"
                            + "若要清掉遗留到期行可执行 UPDATE webhook_delivery SET next_retry = NULL",
                            due.size());
                }
                return;
            }
            for (Long id : due) {
                dispatcher.submit(() -> attempt(id));
            }
        } catch (RuntimeException e) {
            // 表不存在（H2 测试上下文绕过 Flyway，NoKeyStartupTest）/DB 抖动：本轮跳过，下轮再扫，不刷 ERROR
            log.debug("Webhook 重试扫描跳过：{}", e.getMessage());
        }
    }

    /** 单次尝试：先自增 attempts 认领（原子），投递结果写回 last_status / next_retry。 */
    void attempt(long deliveryId) {
        Map<String, Object> row = jdbc.query("""
                UPDATE webhook_delivery SET attempts = attempts + 1, next_retry = NULL
                WHERE id = ? AND next_retry IS NOT NULL
                RETURNING attempts, task_id, url, event
                """, rs -> rs.next() ? Map.of(
                "attempts", rs.getInt("attempts"),
                "task_id", rs.getLong("task_id"),
                "url", rs.getString("url"),
                "event", rs.getString("event")) : null, deliveryId);
        if (row == null) {
            return; // 已被其它扫描/初次投递认领
        }
        int attempts = (Integer) row.get("attempts");
        long taskId = (Long) row.get("task_id");
        String url = (String) row.get("url");
        String event = (String) row.get("event");
        String deliveryUuid = deliveryIdFor(deliveryId).toString();
        try {
            Built built = buildPayload(deliveryUuid, event, taskId);
            byte[] body = mapper.writeValueAsBytes(built.payload());
            // T4.86/P-26：X-Request-Id 回退判定在 service——任务行 request_id 为空（历史/非 HTTP 入口）→ "task-<taskId>"
            String requestIdHeader = built.requestId() != null && !built.requestId().isBlank()
                    ? built.requestId() : "task-" + taskId;
            int statusCode = sender.send(url, event, deliveryUuid, requestIdHeader, body);
            boolean success = statusCode >= 200 && statusCode < 300;
            recordAttempt(deliveryId, statusCode, success ? null : nextBackoffSec(attempts, maxRetries));
            if (success) {
                log.info("Webhook 投递成功：delivery={} task={} event={} HTTP {}", deliveryUuid, taskId, event, statusCode);
            } else if (attempts <= maxRetries) {
                log.warn("Webhook 投递失败（HTTP {}）：{}s 后第 {}/{} 次重试，delivery={} task={}",
                        statusCode, nextBackoffSec(attempts, maxRetries), attempts, maxRetries, deliveryUuid, taskId);
            } else {
                log.error("Webhook 最终投递失败（HTTP {}，已试 {} 次）：delivery={} task={} 留表可观测",
                        statusCode, attempts, deliveryUuid, taskId);
            }
        } catch (ApiException e) {
            // T4.83/P-33：目标地址被 UrlGuard 拒绝——重试也不会变好，直接终局，留表可观测
            recordAttempt(deliveryId, STATUS_URL_BLOCKED, null);
            log.warn("Webhook 目标地址被拒（{}），放弃投递：url={} task={} delivery={}",
                    e.getCode(), url, taskId, deliveryUuid);
        } catch (Exception e) {
            // 认领后投递本身抛异常（payload 组装/网络层失败等）：按失败记账，不静默吞
            recordAttempt(deliveryId, 0, nextBackoffSec(attempts, maxRetries));
            log.warn("Webhook 投递异常：delivery={} task={}（{}），留表重试", deliveryUuid, taskId, e.getMessage());
        }
    }

    /** 失败后下次重试等待秒数（1s/10s/60s 档）；超过重试预算返回 null（终局，留表可观测）。 */
    static Long nextBackoffSec(int attempts, int maxRetries) {
        return attempts <= maxRetries ? BACKOFF_SECONDS[Math.min(attempts, BACKOFF_SECONDS.length) - 1] : null;
    }

    /** 写回本次尝试结果：2xx → next_retry=NULL（完成）；失败 → now()+退避 或终局置 NULL（留表）。 */
    private void recordAttempt(long deliveryId, int statusCode, Long backoffSec) {
        if (backoffSec == null) {
            jdbc.update("UPDATE webhook_delivery SET last_status = ?, next_retry = NULL WHERE id = ?",
                    statusCode, deliveryId);
        } else {
            jdbc.update("UPDATE webhook_delivery SET last_status = ?, "
                            + "next_retry = now() + make_interval(secs => ?) WHERE id = ?",
                    statusCode, backoffSec.doubleValue(), deliveryId);
        }
    }

    /** buildPayload 的产物：九键契约载荷 + 请求关联 id（X-Request-Id 头用，不进载荷——契约九键不变）。 */
    record Built(Map<String, Object> payload, String requestId) {}

    /** 契约载荷（接入指南 §4.2 九键）：查行后委托 {@link #payloadFromRow}（纯函数，可单测）；request_id 从行取但不进载荷。 */
    Built buildPayload(String deliveryId, String event, long taskId) {
        Map<String, Object> result = jdbc.query("""
                SELECT t.status, r.ai_tier, r.summary, r.todos, t.request_id
                FROM task t LEFT JOIN task_result r ON r.task_id = t.id WHERE t.id = ?
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", rs.getString("status"));
            row.put("ai_tier", rs.getString("ai_tier"));
            row.put("summary", rs.getString("summary"));
            row.put("todos", rs.getString("todos"));
            row.put("request_id", rs.getString("request_id")); // T4.86：不进九键载荷，走 X-Request-Id 头
            return row;
        }, taskId);
        if (result == null) {
            throw new IllegalStateException("任务 " + taskId + " 不存在，无法构建 Webhook 载荷");
        }
        return new Built(payloadFromRow(deliveryId, event, taskId, result),
                (String) result.get("request_id"));
    }

    /** 由 task/task_result 行组装九键载荷（键名逐字对齐接入指南 §4.2，Map 字面键即 snake_case）。 */
    Map<String, Object> payloadFromRow(String deliveryId, String event, long taskId, Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("delivery_id", deliveryId);
        payload.put("task_id", taskId);
        payload.put("status", row.get("status"));
        payload.put("ai_tier", row.get("ai_tier"));
        payload.put("occurred_at", Instant.now().toString());
        payload.put("result_url", baseUrl + "/api/v1/tasks/" + taskId);
        payload.put("summary", row.get("summary"));
        payload.put("todos_count", todosCount((String) row.get("todos")));
        return payload;
    }

    /** todos JSONB 数组长度（null/坏数据 → 0，D-072：null=没生成 也按 0 报）。 */
    private int todosCount(String todosRaw) {
        if (todosRaw == null || todosRaw.isBlank()) {
            return 0;
        }
        try {
            var node = mapper.readTree(todosRaw);
            return node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** T4.96：任务在回收站？行不存在 = false（走原口径）。 */
    private boolean isTrashed(long taskId) {
        return jdbc.query("SELECT 1 FROM task WHERE id = ? AND deleted_at IS NOT NULL",
                rs -> rs.next() ? 1 : null, taskId) != null;
    }

    /** task.callback_url 优先，否则全局 URL；两者都空返回 null（不投递）。 */
    private String resolveUrl(long taskId) {
        String callback = jdbc.query("SELECT COALESCE(callback_url, '') FROM task WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
        if (callback != null && !callback.isBlank()) {
            return callback.trim();
        }
        return globalUrl.isEmpty() ? null : globalUrl;
    }

    /** 事件名映射：DONE→task.done，DEAD→task.failed（只推终态）。 */
    static String eventFor(String status) {
        return switch (status) {
            case "DONE" -> "task.done";
            case "DEAD" -> "task.failed";
            default -> throw new IllegalArgumentException("Webhook 只支持终态，收到：" + status);
        };
    }

    /** 行 id → 确定性 UUID（v3，名字空间法）：同一次投递的所有重试共用一个去重键。 */
    static UUID deliveryIdFor(long rowId) {
        return UUID.nameUUIDFromBytes(("mn-webhook-delivery-" + rowId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
