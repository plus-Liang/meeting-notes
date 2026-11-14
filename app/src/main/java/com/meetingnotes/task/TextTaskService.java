package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.upload.SubtitleParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文本任务提交（T3.7 / D-084）：PASTE（JSON 文本）与字幕（srt/ass 解析后）共用一条路径。
 *
 * <p>与音频路径（{@link TaskService}）**逐条同序**的幂等/配额/存储口径：
 * ①先解析得到**最终文本**（字幕在 controller 侧已解析）→ hash 基于 UTF-8 字节（裁决 1：
 * {@code content_hash == sha256(MinIO 对象字节)} 全项目不变量，srt/ass/同文本不建重复任务）；
 * ②`INSERT … ON CONFLICT DO NOTHING`（冲突不耗配额，TOCTOU 安全）→ `consume(userId, 0)`
 * （+1 任务 / +0 分钟，429 回滚）→ 存 MinIO `paste.txt` → 回填 source_uri → 入队；
 * ③幂等命中返回库内真实状态，命中 DEAD 复活重投并清死信（D-049）。
 * `source_type=PASTE`（V1 注释预留值）；`duration_sec=NULL`（无音频时长）。
 */
@Service
public class TextTaskService {

    private static final Logger log = LoggerFactory.getLogger(TextTaskService.class);

    /** 认证体系接入前固定单用户（与 TaskService 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    /** PASTE/字幕解析后文本上限（卡 2：trim 后 1..100_000 字符）。 */
    static final int MAX_TEXT_CHARS = 100_000;

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final QuotaService quotaService;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactionTemplate;
    private final String bucket;
    private final String queueKey;

    public TextTaskService(JdbcTemplate jdbc,
                           S3Client s3,
                           QuotaService quotaService,
                           StringRedisTemplate redis,
                           TransactionTemplate transactionTemplate,
                           @Value("${app.storage.bucket}") String bucket,
                           @Value("${app.worker.queue-key}") String queueKey) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.quotaService = quotaService;
        this.redis = redis;
        this.transactionTemplate = transactionTemplate;
        this.bucket = bucket;
        this.queueKey = queueKey;
    }

    /** JSON 文本 PASTE 入口（controller 已保证非 null body）。 */
    public TaskCreatedResponse createPaste(String text) {
        return createTextTask(text);
    }

    /** 字幕入口：413 大小闸 → 解码 → 解析 → 得到最终文本后与 PASTE 同路（解析为空 → 400）。 */
    public TaskCreatedResponse createSubtitle(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "INVALID_MEDIA", "multipart 字段 file 缺失或为空文件");
        }
        if (file.getSize() > SubtitleParser.MAX_SUBTITLE_BYTES) {
            throw new ApiException(413, "SIZE_EXCEEDED",
                    "字幕文件 " + file.getSize() + " 字节，超过上限 " + SubtitleParser.MAX_SUBTITLE_BYTES);
        }
        String content = SubtitleParser.decode(getBytes(file));
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String text = name.endsWith(".ass")
                ? SubtitleParser.parseAss(content)
                : SubtitleParser.parseSrt(content);
        if (text.isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "字幕文件没有可识别文本");
        }
        return createTextTask(text);
    }

    private byte[] getBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new ApiException(400, "INVALID_PARAM", "字幕文件读取失败");
        }
    }

    private TaskCreatedResponse createTextTask(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM", "text 不能为空");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ApiException(400, "INVALID_PARAM", "text 不能超过 " + MAX_TEXT_CHARS + " 字符");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(bytes);
        Long taskId = transactionTemplate.execute(tx -> {
            Long newId = jdbc.query("""
                            INSERT INTO task (user_id, source_type, content_hash, duration_sec, size_bytes, status)
                            VALUES (?, 'PASTE', ?, NULL, ?, 'PENDING')
                            ON CONFLICT (user_id, content_hash) DO NOTHING
                            RETURNING id
                            """,
                    rs -> rs.next() ? rs.getLong(1) : null,
                    DEFAULT_USER_ID, sha256, (long) bytes.length);
            if (newId == null) {
                return null;
            }
            quotaService.consume(DEFAULT_USER_ID, 0);
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey(newId)).build(),
                        RequestBody.fromBytes(bytes));
            } catch (software.amazon.awssdk.core.exception.SdkException e) {
                log.error("任务 {} 写入文本对象失败", newId, e);
                throw new ApiException(500, "STORAGE_ERROR", "对象存储写入失败，请检查 MinIO 服务是否可用");
            }
            jdbc.update("UPDATE task SET source_uri = ?, updated_at = now() WHERE id = ?",
                    "s3://" + bucket + "/" + objectKey(newId), newId);
            return newId;
        });
        if (taskId == null) {
            TextTaskService.ExistingTask existing = findIdAndStatusByHash(DEFAULT_USER_ID, sha256);
            if (existing == null) {
                throw new ApiException(500, "INTERNAL_ERROR", "幂等冲突回查未找到已有任务，请联系管理员");
            }
            if ("DEAD".equals(existing.status())) {
                jdbc.update("""
                        UPDATE task SET attempts = 0, status = 'PENDING', error_code = NULL, updated_at = now()
                        WHERE id = ?
                        """, existing.id());
                jdbc.update("DELETE FROM dead_letter WHERE task_id = ?", existing.id());
                enqueue(existing.id());
                log.info("文本任务 {} 已 DEAD，重复提交触发复活重投（D-049）", existing.id());
                return new TaskCreatedResponse(existing.id(), "PENDING", sha256, null, bytes.length, true);
            }
            return new TaskCreatedResponse(existing.id(), existing.status(), sha256, null, bytes.length, true);
        }
        enqueue(taskId);
        return new TaskCreatedResponse(taskId, "PENDING", sha256, null, bytes.length, false);
    }

    /** MinIO 对象 key：tasks/{taskId}/paste.txt（worker 按 source_type=PASTE 读文本）。 */
    private static String objectKey(long taskId) {
        return "tasks/" + taskId + "/paste.txt";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void enqueue(long taskId) {
        try {
            redis.opsForList().leftPush(queueKey, Long.toString(taskId));
        } catch (DataAccessException e) {
            log.error("任务 {} 入队失败（Redis 不可用），停在 PENDING，待 T2.3 恢复机制兜底", taskId, e);
        }
    }

    record ExistingTask(long id, String status) {
    }

    private ExistingTask findIdAndStatusByHash(long userId, String sha256) {
        return jdbc.query(
                "SELECT id, status FROM task WHERE content_hash = ? AND user_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? new ExistingTask(rs.getLong(1), rs.getString(2)) : null,
                sha256, userId);
    }
}
