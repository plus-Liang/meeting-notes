package com.meetingnotes.task;

import com.meetingnotes.upload.AudioDurationProbe;
import com.meetingnotes.upload.MediaMagic;
import com.meetingnotes.web.ApiException;
import com.meetingnotes.web.TaskCreatedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * UPLOAD 入口（T1.4 / plan §7 步骤 1–5）。
 *
 * <p>校验顺序严格按卡片：magic number（只信文件头，spec N6）→ 大小 → 时长 →
 * 幂等（N1/N10：同 hash 直接返回已有任务，不消耗配额）→ 配额（N8）→
 * 写 task(PENDING) → 存 MinIO（按 taskId 分目录）→ 回填 source_uri。
 *
 * <p>content_hash 全程流式计算（边拷贝到临时文件边喂 SHA-256），不整文件读进内存。
 * Redis 投递与 worker 消费属 T1.5，本任务只到 task(PENDING)。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 认证体系接入前（spec US-9 属后续卡），先固定单用户。 */
    private static final long DEFAULT_USER_ID = 1L;
    private static final int HEAD_BYTES = 16;

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final AudioDurationProbe probe;
    private final QuotaService quotaService;
    private final StringRedisTemplate redis;
    private final long maxSizeBytes;
    private final long maxDurationSec;
    private final String bucket;
    private final String queueKey;

    public TaskService(JdbcTemplate jdbc,
                       S3Client s3,
                       AudioDurationProbe probe,
                       QuotaService quotaService,
                       StringRedisTemplate redis,
                       @Value("${app.task.max-size-bytes}") long maxSizeBytes,
                       @Value("${app.task.max-duration-sec}") long maxDurationSec,
                       @Value("${app.storage.bucket}") String bucket,
                       @Value("${app.worker.queue-key}") String queueKey) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.probe = probe;
        this.quotaService = quotaService;
        this.redis = redis;
        this.maxSizeBytes = maxSizeBytes;
        this.maxDurationSec = maxDurationSec;
        this.bucket = bucket;
        this.queueKey = queueKey;
    }

    public TaskCreatedResponse createUpload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "INVALID_MEDIA", "multipart 字段 file 缺失或为空文件");
        }

        // 1) magic number：读前 16 字节判断，不落盘、不信扩展名/MIME
        byte[] head = new byte[HEAD_BYTES];
        int headLen;
        try (InputStream in = file.getInputStream()) {
            headLen = in.readNBytes(head, 0, head.length);
        }
        String format = MediaMagic.detect(java.util.Arrays.copyOf(head, headLen));
        if (format == null) {
            throw new ApiException(400, "INVALID_MEDIA",
                    "文件头无法识别为受支持的音/视频格式（支持清单见 spec §4，扩展名与 MIME 不作为依据）");
        }

        // 2) 大小：servlet 层已有 max-file-size 硬闸；这里流式复核并计算 hash
        if (file.getSize() > maxSizeBytes) {
            throw new ApiException(413, "SIZE_EXCEEDED", "文件 " + file.getSize() + " 字节，超过上限 " + maxSizeBytes);
        }
        Path tmp = Files.createTempFile("mn-upload-", ".bin");
        try {
            String sha256 = streamToTempWithHash(file, tmp);
            long size = Files.size(tmp);

            // 3) 时长：ffprobe 读容器元数据（容器内 ffmpeg，D-010）
            long durationSec = (long) Math.ceil(probe.durationSeconds(tmp));
            if (durationSec > maxDurationSec) {
                throw new ApiException(400, "DURATION_EXCEEDED",
                        "时长 " + durationSec + " 秒，超过上限 " + maxDurationSec + " 秒（D-028：可配至 120 分钟）");
            }

            // 4) 幂等（N1/N10）：同内容重复提交直接返回已有任务（库里的真实 status，D-041），
            //    不重复入列、不消耗配额；查询必须带 user_id（D-040，认证上线前先守住数据约束）
            ExistingTask existing = findIdAndStatusByHash(DEFAULT_USER_ID, sha256);
            if (existing != null) {
                return new TaskCreatedResponse(existing.id(), existing.status(), sha256, durationSec, size, true);
            }

            // 5) 配额（N8）：按向上取整分钟数消费
            int minutes = (int) Math.max(1, Math.ceil(durationSec / 60.0));
            quotaService.consume(DEFAULT_USER_ID, minutes);

            // 6) 写 task(PENDING) → MinIO（tasks/{taskId}/）→ 回填 source_uri
            Long taskId = jdbc.queryForObject(
                    """
                    INSERT INTO task (user_id, source_type, content_hash, duration_sec, size_bytes, status)
                    VALUES (?, 'UPLOAD', ?, ?, ?, 'PENDING')
                    RETURNING id
                    """,
                    Long.class, DEFAULT_USER_ID, sha256, durationSec, size);
            String key = "tasks/" + taskId + "/" + sanitizeFilename(file.getOriginalFilename(), format);
            putObjectEnsuringBucket(key, tmp);
            jdbc.update("UPDATE task SET source_uri = ?, updated_at = now() WHERE id = ?",
                    "s3://" + bucket + "/" + key, taskId);

            // 7) 投递 worker 队列（T1.5）。入队失败不回滚任务：任务停在 PENDING，
            //    由 T2.3 的恢复机制兜底，避免"客户端拿到 500 后重试 → 幂等命中 → 永不入队"。
            try {
                redis.opsForList().leftPush(queueKey, Long.toString(taskId));
            } catch (DataAccessException e) {
                log.error("任务 {} 入队失败（Redis 不可用），停在 PENDING，待 T2.3 恢复机制兜底", taskId, e);
            }

            return new TaskCreatedResponse(taskId, "PENDING", sha256, durationSec, size, false);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 流式拷贝到临时文件并计算 SHA-256；超过大小上限立刻中止（不读满整个流）。 */
    private String streamToTempWithHash(MultipartFile file, Path tmp) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxSizeBytes) {
                    throw new ApiException(413, "SIZE_EXCEEDED", "文件超过大小上限 " + maxSizeBytes + " 字节");
                }
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 上传对象；bucket 不存在（启动时的尽力确认没赶上 MinIO 就绪）则建桶后重试一次。
     */
    private void putObjectEnsuringBucket(String key, Path file) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(file));
        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            s3.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder()
                    .bucket(bucket).build());
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(file));
        }
    }

    /** 幂等命中时返回的已有任务摘要：id + 库里真实状态。 */
    record ExistingTask(long id, String status) {
    }

    /** 按内容 hash 查已有任务；必须带 user_id（D-040），防止未来跨用户读到他人任务。 */
    private ExistingTask findIdAndStatusByHash(long userId, String sha256) {
        return jdbc.query(
                "SELECT id, status FROM task WHERE content_hash = ? AND user_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? new ExistingTask(rs.getLong(1), rs.getString(2)) : null,
                sha256, userId);
    }

    /** 只留文件名主体与安全字符，防路径穿越；空/异常名回退为检测出的格式名。 */
    private static String sanitizeFilename(String original, String format) {
        String base = StringUtils.getFilename(original);
        if (!StringUtils.hasText(base)) {
            return "upload." + format;
        }
        base = base.replace('\\', '/').substring(base.replace('\\', '/').lastIndexOf('/') + 1);
        base = base.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (base.isBlank() || base.startsWith(".")) {
            base = "upload." + format;
        }
        return base.length() > 128 ? base.substring(base.length() - 128) : base;
    }
}
