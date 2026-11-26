package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 转写 worker（T1.5 / plan §7 步骤 6–8）。队列消费用 Redis BLPOP（阻塞取，不轮询空转）；单线程顺序处理。
 * 状态机：PENDING/RETRYING → RUNNING → DONE；失败：重试未耗尽 → RETRYING 重新入队（队尾，FIFO 不插队），
 * 耗尽 → DEAD + dead_letter；兜底 catch 保证任务不永久卡 RUNNING。
 * <p>T4.79/P-02：取出即 ZADD「处理中」回执（processingKey，score=取出毫秒）、结束 ZREM——BLPOP 取走即消失，
 * 没有回执就没有「这条还在处理中」的持久记录；SIGKILL/OOM 后残留回执由 StalePendingSweeper 的可见性超时回收。
 * <p>管线：MinIO 拉文件 → ffmpeg 抽 16k 单声道 wav（D-010）→ AsrClient 一次调用拿 text + segments（T4.35a，
 * 容器内 http://asr:8000，D-034）→ 写 transcript+segments → 结构化（T3.3）→ AI 加标点（T4.18）→ 最后置 DONE（D-069）。
 */
@Component
public class TranscriptionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final com.meetingnotes.asr.AsrClient asrClient;
    private final com.meetingnotes.ai.StructuringService structuringService;
    private final com.meetingnotes.ai.TranscriptPunctuator punctuator;
    private final com.meetingnotes.webhook.WebhookDeliveryService webhookService;
    private final PasteTextReader pasteTextReader;
    private final MonoWavExtractor monoWavExtractor;
    private final com.meetingnotes.task.QueuePublisher queuePublisher;
    private final com.meetingnotes.task.DeadTaskRecorder deadTaskRecorder;
    private final TaskHeartbeat taskHeartbeat; private final TaskAttempts taskAttempts; // T4.94/P-03：attempts 可移植原语（去 RETURNING，H2 可测）
    private final String queueKey;
    private final Duration pollTimeout;
    private final String bucket; private final int maxRetries; private final RedisUnavailableReporter reporter;

    private volatile boolean running;
    private Thread workerThread;
    @Value("${app.worker.drain-timeout-sec:60}")
    private long drainTimeoutSec = 60; // T4.74/P-19：排空上限；纯 new（测试）走 60 默认，Spring 经 @Value 注入 yml 值
    private volatile long inflightTaskId; // T4.74：在飞任务 id（0 = 空闲），stop() 依此排空
    @Value("${app.worker.processing-key:meetingnotes:worker-processing}")
    private String processingKey = "meetingnotes:worker-processing"; // T4.79/P-02：处理中回执 ZSET（取出 ZADD/结束 ZREM）

    public TranscriptionWorker(StringRedisTemplate redis, JdbcTemplate jdbc, S3Client s3,
            com.meetingnotes.asr.AsrClient asrClient, com.meetingnotes.ai.StructuringService structuringService,
            com.meetingnotes.ai.TranscriptPunctuator punctuator, com.meetingnotes.webhook.WebhookDeliveryService webhookService,
            PasteTextReader pasteTextReader, MonoWavExtractor monoWavExtractor, com.meetingnotes.task.QueuePublisher queuePublisher,
            com.meetingnotes.task.DeadTaskRecorder deadTaskRecorder,
            TaskHeartbeat taskHeartbeat, @Value("${app.worker.queue-key}") String queueKey,
            @Value("${app.worker.poll-timeout-sec}") long pollTimeoutSec,
            @Value("${app.storage.bucket}") String bucket, @Value("${app.task.max-retries}") int maxRetries,
            RedisUnavailableReporter reporter, TaskAttempts taskAttempts) {
        this.redis = redis; this.jdbc = jdbc; this.s3 = s3; this.asrClient = asrClient;
        this.structuringService = structuringService; this.punctuator = punctuator; this.webhookService = webhookService;
        this.pasteTextReader = pasteTextReader; this.monoWavExtractor = monoWavExtractor; this.queuePublisher = queuePublisher;
        this.deadTaskRecorder = deadTaskRecorder;
        this.taskHeartbeat = taskHeartbeat; this.queueKey = queueKey; this.pollTimeout = Duration.ofSeconds(pollTimeoutSec); this.bucket = bucket; this.maxRetries = maxRetries; this.reporter = reporter; this.taskAttempts = taskAttempts;
    }

    @Override
    public void start() {
        running = true;
        workerThread = new Thread(this::loop, "transcription-worker");
        workerThread.setDaemon(true); workerThread.start();
        log.info("worker 已启动：queue={}（BLPOP 阻塞取，超时 {}s 仅用于优雅停机）", queueKey, pollTimeout.getSeconds());
    }

    /** T4.74/P-19 三段式：空闲立即中断；在跑排空（上限 drainTimeoutSec）；到点中断交启动恢复。50ms 轮询而非 join（interrupt 时 join 提前返回语义不清）。 */
    @Override
    public void stop() {
        running = false;
        if (inflightTaskId == 0) {
            log.info("优雅停机：无在跑任务，立即中断"); if (workerThread != null) workerThread.interrupt(); return;
        }
        log.info("优雅停机：等待任务 {} 完成（最长 {}s）", inflightTaskId, drainTimeoutSec);
        long deadline = System.currentTimeMillis() + drainTimeoutSec * 1000L;
        while (inflightTaskId != 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (inflightTaskId != 0) {
            log.warn("优雅停机：任务 {} 超过 {}s 未完成，中断并交启动恢复", inflightTaskId, drainTimeoutSec); if (workerThread != null) workerThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() { return running; }

    private void loop() {
        while (running) {
            String taskId;
            try {
                taskId = redis.opsForList().leftPop(queueKey, pollTimeout);
            } catch (RuntimeException e) {
                if (!running) { break; }
                reporter.report(e); // T4.91/S2：去抖——窗口内最多 1 条 WARN（RedisUnavailableReporter）
                sleepQuietly();
                continue;
            }
            // T4.79-fix F1 双保险：单条消息的任何异常不终结消费循环（handleOne 内部已全捕获，此处兜 Redis 侧漏网）；F2：回执写失败（已退回队尾）本轮不处理
            try { reporter.clear(); if (taskId == null) { continue; } if (markProcessing(taskId)) { handleOne(taskId); } } catch (RuntimeException e) { log.error("消费循环单次异常，继续", e); }
        }
        log.info("worker 已停止");
    }

    /** 取一条 → 认领 → 处理（包级可见供 T4.77-fix/T4.79 测试驱动）。认领纳入保护伞：行不存在/DB 瞬时故障只 error 后跳过该条
     *  （不 markFailure——行不存在时认领 SELECT 会抛 EmptyResultDataAccessException），worker 绝不停摆；已认领后 process 异常仍走 log.error + markFailure。 */
    void handleOne(String taskId) {
        TaskLogContext.open(taskId.trim(), null); // T2.7：MDC 防线程复用串号；T4.86：requestId 在认领后补写（TaskLogContext.open 幂等覆盖）
        Claimed claimed = null;
        try {
            long id = Long.parseLong(taskId.trim()); claimed = claim(id); // T4.79-fix F1：坏成员也进保护伞（解析/认领异常同口径跳过）
            if (claimed == null) { log.warn("任务 {} 已被其它实例认领（状态已变更），跳过", id); }
            else { try { process(claimed.row(), id); } catch (Exception e) { log.error("处理异常", e); markFailure(id, e); } }
        } catch (Exception e) { // T4.77-fix B1 + T4.79-fix F1：认领异常/坏成员 → worker 不死
            log.error("队列成员非法，跳过该条继续（worker 不停）：{}", taskId, e);
        } finally {
            if (claimed != null) { claimed.hb().close(); } // T4.77：Session 在 finally 关（异常路径也要停）
            removeProcessingQuietly(taskId); // T4.79/P-02：无论成败/认领与否都清回执（僵尸回执会堆积）
            TaskLogContext.close(); // T4.86/P-26：清 taskId + requestId 两键（不用 clear()，不误伤其它 MDC 键）
            inflightTaskId = 0; // T4.74：成功/失败/异常退出都要把「在跑」标记归零
        }
    }
    /** T4.79/P-02：取出即 ZADD 回执（score=取出毫秒）。ZADD 失败必须退回队尾——绝不允许「既没回执又不在队列」；退回也失败时 DB 行仍是 PENDING（未认领），由清扫兜底。包级可见供测试。 */
    boolean markProcessing(String taskId) { // T4.79-fix F2：true=回执已写、可处理；false=失败（任务已退回队尾），本轮不处理
        try { redis.opsForZSet().add(processingKey, taskId.trim(), System.currentTimeMillis()); return true; }
        catch (RuntimeException e) {
            log.error("回执 ZADD 失败（Redis 抖动），任务 {} 退回队尾", taskId, e); if (!queuePublisher.tryPublishToTail(Long.parseLong(taskId.trim()))) { log.error("任务 {} 退回队尾也失败（Redis 不可用），行仍在 PENDING 待清扫兜底", taskId); } return false;
        }
    }

    /** T4.79/P-02：处理结束清回执（成功/失败/异常/认领失败全路径）；清理失败只 WARN，残留由可见性超时清扫兜底。 */
    private void removeProcessingQuietly(String taskId) {
        try {
            redis.opsForZSet().remove(processingKey, taskId.trim());
        } catch (RuntimeException e) {
            log.warn("回执 ZREM 失败（任务 {}），留待可见性超时清扫兜底", taskId, e);
        }
    }
    /** 认领结果（非 null = 成功，hb 已启动）；row 供管线读 source_uri/source_type/attempts。 */
    private record Claimed(TaskHeartbeat.Session hb, Map<String, Object> row) {}
    /** T4.77/P-06：原子认领（SQL 语义不变）+ 在飞标记 + 心跳启动；返回 null = 已被其它实例认领（跳过）。 */
    private Claimed claim(long taskId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, source_type, COALESCE(source_uri, '') AS source_uri, COALESCE(request_id, '') AS request_id FROM task WHERE id = ?", taskId);
        int claimed = jdbc.update("UPDATE task SET status = 'RUNNING', updated_at = now() WHERE id = ? "
                + "AND status IN ('PENDING','RETRYING')", taskId);
        if (claimed == 0) { return null; }
        TaskLogContext.open(String.valueOf(taskId), (String) row.get("request_id")); // T4.86/P-26：认领后补写 requestId（历史任务空串 → 跳过不写）
        inflightTaskId = taskId; // T4.74：认领成功即标记在飞，stop() 依此排空
        log.info("开始转写（第 {} 次尝试）", ((Number) row.get("attempts")).intValue() + 1);
        return new Claimed(taskHeartbeat.start(taskId), row);
    }

    private void process(Map<String, Object> row, long taskId) throws Exception {
        String sourceUri = (String) row.get("source_uri");
        if (!sourceUri.startsWith("s3://" + bucket + "/")) {
            throw new ProcessingException("STORAGE_ERROR", "source_uri 非法：" + sourceUri, null);
        }
        String key = sourceUri.substring(("s3://" + bucket + "/").length());
        // T3.7：PASTE/字幕任务——直接读文本对象，跳过 ffmpeg/ASR（管线仍守 F-1/D-069：transcript → 结构化 → DONE）
        if ("PASTE".equals(row.get("source_type"))) {
            String text;
            try {
                text = pasteTextReader.read(key);
            } catch (RuntimeException e) {
                throw new ProcessingException("STORAGE_ERROR", "读取文本对象失败：" + e.getMessage(), e);
            }
            log.info("PASTE 文本已读取（{} 字），跳过 ASR", text == null ? 0 : text.length());
            finalizeResult(taskId, text, null); // PASTE 无音频，segments 恒 null（不是 []）
            return;
        }
        Path dir = null;
        Path wav = null;
        try {
            // 1) MinIO 拉原始文件（临时目录内建文件，toFile 以 CREATE_NEW 打开，不能预创建）
            dir = Files.createTempDirectory("mn-job-");
            Path input = dir.resolve("input.bin");
            try {
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                        ResponseTransformer.toFile(input));
            } catch (RuntimeException e) {
                throw new ProcessingException("STORAGE_ERROR", "从对象存储拉取文件失败：" + e.getMessage(), e);
            }
            log.info("拉取媒体对象完成（{} bytes）", Files.size(input));
            // 2) ffmpeg 抽 16k 单声道 wav（卡片指定参数）
            wav = Files.createTempFile("mn-wav-", ".wav");
            monoWavExtractor.extract(input, wav);
            log.info("ffmpeg 抽音轨完成（16k 单声道 wav）");
            // 3) ASR 转写（T4.35a：一次调用拿 text + segments；RuntimeException → ProcessingException("ASR_ERROR") 走既有重试/死信）
            String text;
            java.util.List<com.meetingnotes.asr.AsrResponseParser.Segment> segments;
            try {
                com.meetingnotes.asr.AsrClient.Transcription result = asrClient.transcribe(wav);
                text = result.text();
                segments = result.segments();
            } catch (RuntimeException e) {
                throw new ProcessingException("ASR_ERROR", "ASR 转写调用失败：" + e.getMessage(), e);
            }
            log.info("ASR 转写完成（{} 字，{} 段）", text == null ? 0 : text.length(),
                    segments == null ? 0 : segments.size());
            // 4) 收口（finalizeResult）：写 transcript + segments → 摘要/待办（AI 失败只 WARN 留空，T3.3 接管）→ 最后置 DONE；空文本照常（D-048）
            finalizeResult(taskId, text, segments);
        } finally {
            if (dir != null) { deleteRecursively(dir); }
            if (wav != null) { Files.deleteIfExists(wav); }
        }
    }

    /** 结果收口（F-1/T3.2，D-069）：transcript+segments → 结构化 → 标点 → **最后**置 DONE；segments 与 transcript 同一语句写入（T4.35a，无音频/PASTE 传 null）。 */
    void finalizeResult(long taskId, String text,
                        java.util.List<com.meetingnotes.asr.AsrResponseParser.Segment> segments) {
        saveTranscript(taskId, text, segments);
        boolean structured = runAiStep("结构化", () -> structuringService.structure(taskId, text));
        if (structured) {
            runAiStep("标点", () -> punctuator.punctuateAndPersist(taskId, text));
        }
        jdbc.update("UPDATE task SET status = 'DONE', updated_at = now() WHERE id = ?", taskId);
        log.info("任务已置 DONE（transcript/segments/摘要/待办均已收口）");
        webhookService.onTerminal(taskId, "DONE"); // T4.6：终态异步投递 Webhook（内部不抛、不阻塞）
    }

    /** D-048：null/空白归一化为空串落 task_result（不算失败不重试）；segments（jsonb）与 transcript 同一 upsert 写入，null 不建索引。 */
    void saveTranscript(long taskId, String text,
                        java.util.List<com.meetingnotes.asr.AsrResponseParser.Segment> segments) {
        String normalized = text == null ? "" : text;
        jdbc.update("""
                INSERT INTO task_result (task_id, transcript, segments) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (task_id) DO UPDATE SET transcript = EXCLUDED.transcript,
                    segments = EXCLUDED.segments, created_at = now()
                """, taskId, normalized, com.meetingnotes.asr.AsrResponseParser.toJson(segments));
        log.info("转写结果已落库（transcript {} 字，segments {}）", normalized.length(),
                segments == null ? "null" : segments.size() + " 段");
    }

    /** AI 单步兜底：异常只 WARN 不上抛（D-068，失败不阻塞任务完成），返回步骤是否成功。 */
    private boolean runAiStep(String name, Callable<Boolean> step) {
        try { return Boolean.TRUE.equals(step.call()); }
        catch (Exception e) { log.warn("{}生成异常（不影响任务完成）", name, e); return false; }
    }

    /** 失败兜底：不永久卡 RUNNING。重试未耗尽 → RETRYING + 重新入队（队尾）；重投失败 → 退回 PENDING；耗尽 → DEAD + dead_letter。 */
    private void markFailure(long taskId, Exception cause) {
        String code = cause instanceof ProcessingException pe ? pe.code : "UNKNOWN_ERROR";
        String reason = abbreviate(cause.getMessage());
        try {
            // T4.94/P-03：RETURNING（PG 专用）→ 可移植两步（自增 UPDATE + 单独 SELECT），SQL 首次可被 H2 真库覆盖
            taskAttempts.bump(taskId);
            Integer attempts = taskAttempts.read(taskId);
            if (attempts != null && attempts >= maxRetries) {
                // T4.87 C3：置 DEAD + dead_letter + 退分钟数收敛到 DeadTaskRecorder（单事务，不变量 DEAD⟺refunded）
                deadTaskRecorder.markDead(taskId, code, reason);
                log.error("任务 {} 重试 {} 次仍失败，置 DEAD（{}）并写 dead_letter：{}", taskId, attempts, code, reason);
                webhookService.onTerminal(taskId, "DEAD"); // T4.6：终态异步投递 Webhook（内部不抛、不阻塞）
            } else {
                jdbc.update("UPDATE task SET status = 'RETRYING', error_code = ?, updated_at = now() WHERE id = ?",
                        code, taskId);
                if (queuePublisher.tryPublishToTail(taskId)) { /* T4.60 B1：失败重投走队尾（rightPush），让出队头给其他任务 */
                    log.warn("任务 {} 失败（{}/{} 次，{}），已置 RETRYING 并重新入队：{}", taskId, attempts, maxRetries, code, reason);
                } else {
                    // T4.52 S6：重投失败不能停在 RETRYING（无人回收）——退回 PENDING，由启动恢复/幂等重提自愈兜底
                    jdbc.update("UPDATE task SET status = 'PENDING', updated_at = now() WHERE id = ?", taskId);
                    log.error("任务 {} 失败（{}/{} 次，{}）后重投失败（Redis 不可用），已退回 PENDING", taskId, attempts, maxRetries, code);
                }
            }
        } catch (RuntimeException e) {
            log.error("任务 {} 写回失败状态时出错（可能卡在 RUNNING，待 T2.3 恢复）", taskId, e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) { /* 清理失败无害 */ } });
        } catch (java.io.IOException ignored) { // 临时目录清理失败无害，留给 OS tmp 清理
        }
    }

    private void sleepQuietly() {
        try { TimeUnit.SECONDS.sleep(pollTimeout.getSeconds()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String abbreviate(String s) {
        return s == null || s.isBlank() ? "UNKNOWN" : s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
