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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 转写 worker（T1.5 / plan §7 步骤 6–8）。
 *
 * <p>队列消费用 Redis BLPOP（阻塞取，不轮询空转）；单线程顺序处理（并发度
 * ASR_MAX_CONCURRENCY 的多 worker 属 T2.x）。状态机：PENDING/RETRYING → RUNNING → DONE，
 * 失败：重试未耗尽 → RETRYING 并重新入队（队尾，FIFO 不插队）；耗尽 → DEAD + dead_letter。
 * 兜底 catch 保证任务不会永久卡在 RUNNING（进程级崩溃恢复属 T2.3）。
 *
 * <p>处理管线：MinIO 拉原始文件 → 容器内 ffmpeg 抽 16k 单声道 wav（D-010）→
 * 复用已配好的 {@link TranscriptionModel}（容器内 http://asr:8000，D-034）→
 * 写 task_result → task.status=DONE。
 */
@Component
public class TranscriptionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final TranscriptionModel transcriptionModel;
    private final String queueKey;
    private final Duration pollTimeout;
    private final String ffmpegBin;
    private final String bucket;
    private final int maxRetries;

    private volatile boolean running;
    private Thread workerThread;

    public TranscriptionWorker(StringRedisTemplate redis,
                               JdbcTemplate jdbc,
                               S3Client s3,
                               TranscriptionModel transcriptionModel,
                               @Value("${app.worker.queue-key}") String queueKey,
                               @Value("${app.worker.poll-timeout-sec}") long pollTimeoutSec,
                               @Value("${app.media.ffmpeg-bin:ffmpeg}") String ffmpegBin,
                               @Value("${app.storage.bucket}") String bucket,
                               @Value("${app.task.max-retries}") int maxRetries) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.s3 = s3;
        this.transcriptionModel = transcriptionModel;
        this.queueKey = queueKey;
        this.pollTimeout = Duration.ofSeconds(pollTimeoutSec);
        this.ffmpegBin = ffmpegBin;
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
            try {
                process(Long.parseLong(taskId.trim()));
            } catch (Exception e) {
                log.error("任务 {} 处理异常", taskId, e);
                markFailure(Long.parseLong(taskId.trim()), e);
            }
        }
        log.info("worker 已停止");
    }

    private void process(long taskId) throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, COALESCE(source_uri, '') AS source_uri FROM task WHERE id = ?", taskId);
        String status = (String) row.get("status");
        if (!"PENDING".equals(status) && !"RETRYING".equals(status)) {
            log.warn("任务 {} 状态为 {}，跳过（不重复处理）", taskId, status);
            return;
        }
        jdbc.update("UPDATE task SET status = 'RUNNING', updated_at = now() WHERE id = ?", taskId);
        log.info("任务 {} 开始转写（第 {} 次尝试）", taskId, ((Number) row.get("attempts")).intValue() + 1);

        int attempts = ((Number) row.get("attempts")).intValue();
        Path dir = null;
        Path wav = null;
        try {
            String sourceUri = (String) row.get("source_uri");
            if (!sourceUri.startsWith("s3://" + bucket + "/")) {
                throw new ProcessingException("STORAGE_ERROR", "source_uri 非法：" + sourceUri, null);
            }
            String key = sourceUri.substring(("s3://" + bucket + "/").length());

            // 1) MinIO 拉原始文件（临时目录内建文件，toFile 以 CREATE_NEW 打开，不能预创建）
            dir = Files.createTempDirectory("mn-job-");
            Path input = dir.resolve("input.bin");
            try {
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                        ResponseTransformer.toFile(input));
            } catch (RuntimeException e) {
                throw new ProcessingException("STORAGE_ERROR", "从对象存储拉取文件失败：" + e.getMessage(), e);
            }

            // 2) ffmpeg 抽 16k 单声道 wav（卡片指定参数）
            wav = Files.createTempFile("mn-wav-", ".wav");
            extractMono16k(input, wav);

            // 3) ASR 转写（复用 TranscriptionModel，容器内 http://asr:8000）
            String text;
            try {
                text = transcriptionModel.transcribe(new FileSystemResource(wav));
            } catch (RuntimeException e) {
                throw new ProcessingException("ASR_ERROR", "ASR 转写调用失败：" + e.getMessage(), e);
            }
            if (text == null || text.isBlank()) {
                throw new ProcessingException("ASR_ERROR", "ASR 返回空文本", null);
            }

            // 4) 写结果 + DONE
            jdbc.update("""
                    INSERT INTO task_result (task_id, transcript) VALUES (?, ?)
                    ON CONFLICT (task_id) DO UPDATE SET transcript = EXCLUDED.transcript, created_at = now()
                    """, taskId, text);
            jdbc.update("UPDATE task SET status = 'DONE', updated_at = now() WHERE id = ?", taskId);
            log.info("任务 {} 完成，转写 {} 字", taskId, text.length());
        } finally {
            if (dir != null) {
                deleteRecursively(dir);
            }
            if (wav != null) {
                Files.deleteIfExists(wav);
            }
        }
    }

    /**
     * 失败兜底：不永久卡 RUNNING。重试未耗尽 → RETRYING + 重新入队；耗尽 → DEAD + dead_letter。
     * error_code 只存稳定错误码（列宽 64）；完整原因写 dead_letter.reason（TEXT）。
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
                } catch (java.io.IOException ignored) {
                    // 临时目录清理失败无害，留给 OS tmp 清理
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }

    private void extractMono16k(Path input, Path wav) throws Exception {
        Process p = new ProcessBuilder(
                ffmpegBin, "-y", "-i", input.toAbsolutePath().toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-f", "wav",
                wav.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String out;
        try (var is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg 抽音轨超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg 失败：" + tail(out, 300));
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
        if (s == null || s.isBlank()) {
            return "UNKNOWN";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
