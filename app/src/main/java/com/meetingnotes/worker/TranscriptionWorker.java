package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.FileSystemResource;
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
import java.util.concurrent.TimeUnit;

/**
 * 转写 worker（T1.5 / plan §7 步骤 6–8）。队列消费用 Redis BLPOP（阻塞取，不轮询空转）；单线程顺序处理。
 * 状态机：PENDING/RETRYING → RUNNING → DONE；失败：重试未耗尽 → RETRYING 重新入队（队尾，FIFO 不插队），
 * 耗尽 → DEAD + dead_letter；兜底 catch 保证任务不永久卡 RUNNING（崩溃恢复属 T2.3）。
 * <p>管线：MinIO 拉文件 → ffmpeg 抽 16k 单声道 wav（D-010）→ {@link TranscriptionModel} 转写
 * （容器内 http://asr:8000，D-034）→ 写 transcript → 结构化（T3.3：摘要+关键词+待办一次调用、三档级联降级）
 * → 最后置 DONE（D-069：DONE 时结果必已落库）。
 */
@Component
public class TranscriptionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final TranscriptionModel transcriptionModel;
    private final com.meetingnotes.ai.StructuringService structuringService;
    private final com.meetingnotes.webhook.WebhookDeliveryService webhookService;
    private final PasteTextReader pasteTextReader;
    private final MonoWavExtractor monoWavExtractor;
    private final String queueKey;
    private final Duration pollTimeout;
    private final String bucket;
    private final int maxRetries;

    private volatile boolean running;
    private Thread workerThread;

    public TranscriptionWorker(StringRedisTemplate redis,
                               JdbcTemplate jdbc,
                               S3Client s3,
                               TranscriptionModel transcriptionModel,
                               com.meetingnotes.ai.StructuringService structuringService,
                               com.meetingnotes.webhook.WebhookDeliveryService webhookService,
                               PasteTextReader pasteTextReader,
                               MonoWavExtractor monoWavExtractor,
                               @Value("${app.worker.queue-key}") String queueKey,
                               @Value("${app.worker.poll-timeout-sec}") long pollTimeoutSec,
                               @Value("${app.storage.bucket}") String bucket,
                               @Value("${app.task.max-retries}") int maxRetries) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.s3 = s3;
        this.transcriptionModel = transcriptionModel;
        this.structuringService = structuringService;
        this.webhookService = webhookService;
        this.pasteTextReader = pasteTextReader;
        this.monoWavExtractor = monoWavExtractor;
        this.queueKey = queueKey;
        this.pollTimeout = Duration.ofSeconds(pollTimeoutSec);
        this.bucket = bucket;
        this.maxRetries = maxRetries;
    }

    @Override
    public void start() {
        running = true;
        workerThread = new Thread(this::loop, "transcription-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("worker 已启动：queue={}（BLPOP 阻塞取，超时 {}s 仅用于优雅停机）", queueKey, pollTimeout.getSeconds());
    }

    @Override
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            String taskId;
            try {
                taskId = redis.opsForList().leftPop(queueKey, pollTimeout);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                log.warn("Redis 不可用，{}s 后重试：{}", pollTimeout.getSeconds(), e.getMessage());
                sleepQuietly();
                continue;
            }
            if (taskId == null) {
                continue; // BLPOP 超时，回到阻塞等待（无空转轮询）
            }
            // T2.7：taskId 放 MDC（结构化日志的独立字段），处理/失败全程贯穿，finally 清防止线程复用串号
            org.slf4j.MDC.put("taskId", taskId.trim());
            try {
                try {
                    process(Long.parseLong(taskId.trim()));
                } catch (Exception e) {
                    log.error("处理异常", e);
                    markFailure(Long.parseLong(taskId.trim()), e);
                }
            } finally {
                org.slf4j.MDC.remove("taskId");
            }
        }
        log.info("worker 已停止");
    }

    private void process(long taskId) throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, source_type, COALESCE(source_uri, '') AS source_uri FROM task WHERE id = ?", taskId);
        String status = (String) row.get("status");
        if (!"PENDING".equals(status) && !"RETRYING".equals(status)) {
            log.warn("任务 {} 状态为 {}，跳过（不重复处理）", taskId, status);
            return;
        }
        jdbc.update("UPDATE task SET status = 'RUNNING', updated_at = now() WHERE id = ?", taskId);
        log.info("开始转写（第 {} 次尝试）", ((Number) row.get("attempts")).intValue() + 1);

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
            finalizeResult(taskId, text);
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

            // 3) ASR 转写（复用 TranscriptionModel，容器内 http://asr:8000）
            String text;
            try {
                text = transcriptionModel.transcribe(new FileSystemResource(wav));
            } catch (RuntimeException e) {
                throw new ProcessingException("ASR_ERROR", "ASR 转写调用失败：" + e.getMessage(), e);
            }
            log.info("ASR 转写完成（{} 字）", text == null ? 0 : text.length());

            // 4) 收口（finalizeResult）：写 transcript → 摘要/待办（AI 失败只 WARN 留空，T3.3 接管）→ 最后置 DONE；空文本照常（D-048）
            finalizeResult(taskId, text);
        } finally {
            if (dir != null) {
                deleteRecursively(dir);
            }
            if (wav != null) {
                Files.deleteIfExists(wav);
            }
        }
    }

    /** 结果收口（F-1/T3.2，D-069）：先写 transcript → 结构化（T3.3 F-7：摘要+关键词+待办**一次**调用）→
     * **最后**置 DONE（AI 失败也 DONE）；外部读到 DONE 时 summary/todos 必已落库，不存在 DONE+null 窗口。 */
    void finalizeResult(long taskId, String text) {
        saveTranscript(taskId, text);
        runAiStep("结构化", () -> structuringService.structure(taskId, text));
        jdbc.update("UPDATE task SET status = 'DONE', updated_at = now() WHERE id = ?", taskId);
        log.info("任务已置 DONE（transcript/摘要/待办均已收口）");
        webhookService.onTerminal(taskId, "DONE"); // T4.6：终态异步投递 Webhook（内部不抛、不阻塞）
    }

    /** D-048：null/空白归一化为空串落 task_result（不算失败不重试，DONE 由 finalizeResult 统一置）。 */
    void saveTranscript(long taskId, String text) {
        String normalized = text == null ? "" : text;
        jdbc.update("""
                INSERT INTO task_result (task_id, transcript) VALUES (?, ?)
                ON CONFLICT (task_id) DO UPDATE SET transcript = EXCLUDED.transcript, created_at = now()
                """, taskId, normalized);
        log.info("转写结果已落库（transcript {} 字）", normalized.length());
    }

    /** AI 单步兜底：异常只 WARN 不上抛（D-068，失败不阻塞任务完成）。 */
    private void runAiStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("{}生成异常（不影响任务完成）", name, e);
        }
    }

    /**
     * 失败兜底：不永久卡 RUNNING。重试未耗尽 → RETRYING + 重新入队；耗尽 → DEAD + dead_letter。
     * error_code 只存稳定错误码（列宽 64），完整原因写 dead_letter.reason（TEXT）。
     */
    private void markFailure(long taskId, Exception cause) {
        String code = cause instanceof ProcessingException pe ? pe.code : "UNKNOWN_ERROR";
        String reason = abbreviate(cause.getMessage());
        try {
            Integer attempts = jdbc.query(
                    "UPDATE task SET attempts = attempts + 1, updated_at = now() WHERE id = ? RETURNING attempts",
                    rs -> rs.next() ? rs.getInt(1) : null, taskId);
            if (attempts != null && attempts >= maxRetries) {
                jdbc.update("UPDATE task SET status = 'DEAD', error_code = ?, updated_at = now() WHERE id = ?",
                        code, taskId);
                jdbc.update("INSERT INTO dead_letter (task_id, reason) VALUES (?, ?)", taskId, reason);
                log.error("任务 {} 重试 {} 次仍失败，置 DEAD（{}）并写 dead_letter：{}", taskId, attempts, code, reason);
                webhookService.onTerminal(taskId, "DEAD"); // T4.6：终态异步投递 Webhook（内部不抛、不阻塞）
            } else {
                jdbc.update("UPDATE task SET status = 'RETRYING', error_code = ?, updated_at = now() WHERE id = ?",
                        code, taskId);
                redis.opsForList().rightPush(queueKey, Long.toString(taskId));
                log.warn("任务 {} 失败（{}/{} 次，{}），已置 RETRYING 并重新入队：{}", taskId, attempts, maxRetries, code, reason);
            }
        } catch (RuntimeException e) {
            log.error("任务 {} 写回失败状态时出错（可能卡在 RUNNING，待 T2.3 恢复）", taskId, e);
        }
    }

    /** 处理失败携带稳定错误码（task.error_code VARCHAR(64) 只放得下码，原因进 dead_letter）。 */
    static final class ProcessingException extends RuntimeException {
        final String code;

        ProcessingException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) { // 临时目录清理失败无害，留给 OS tmp 清理
                }
            });
        } catch (java.io.IOException ignored) { // 同上
        }
    }

    private void sleepQuietly() {
        try {
            TimeUnit.SECONDS.sleep(pollTimeout.getSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s) {
        return s == null || s.isBlank() ? "UNKNOWN" : s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
