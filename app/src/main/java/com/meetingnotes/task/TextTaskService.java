package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.upload.SubtitleParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Locale;

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


    /** PASTE/字幕解析后文本上限（卡 2：trim 后 1..100_000 字符）。 */
    static final int MAX_TEXT_CHARS = 100_000;

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final QuotaService quotaService;
    private final QueuePublisher queuePublisher;
    private final TaskRequeueSupport taskRequeueSupport;
    private final TransactionTemplate transactionTemplate;
    private final String bucket;

    public TextTaskService(JdbcTemplate jdbc,
                           S3Client s3,
                           QuotaService quotaService,
                           QueuePublisher queuePublisher,
                           TaskRequeueSupport taskRequeueSupport,
                           TransactionTemplate transactionTemplate,
                           @Value("${app.storage.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.quotaService = quotaService;
        this.queuePublisher = queuePublisher;
        this.taskRequeueSupport = taskRequeueSupport;
        this.transactionTemplate = transactionTemplate;
        this.bucket = bucket;
    }

    /** JSON 文本 PASTE 入口（controller 已保证非 null body）。 */
    public TaskCreatedResponse createPaste(String text) {
        return createPaste(text, null, null);
    }

    public TaskCreatedResponse createPaste(String text, String template) {
        return createPaste(text, template, null);
    }

    /** PASTE 入口（T3.9 template；T4.99 可选 language，校验与上传共用）。 */
    public TaskCreatedResponse createPaste(String text, String template, String language) {
        return createTextTask(text, template, language);
    }

    /** 字幕入口：413 大小闸 → 解码 → 解析 → 得到最终文本后与 PASTE 同路（解析为空 → 400）。 */
    public TaskCreatedResponse createSubtitle(MultipartFile file) {
        return createSubtitle(file, null);
    }

    /** 字幕入口（T3.9 起带可选 template；字幕同样**不做**文件名自动推荐：显式值否则 meeting）。 */
    public TaskCreatedResponse createSubtitle(MultipartFile file, String template) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "INVALID_MEDIA", "multipart 字段 file 缺失或为空文件");
        }
        if (file.getSize() > SubtitleParser.MAX_SUBTITLE_BYTES) {
            throw new ApiException(413, "SIZE_EXCEEDED",
                    "字幕文件 " + file.getSize() + " 字节，超过上限 " + SubtitleParser.MAX_SUBTITLE_BYTES);
        }
        String content = SubtitleParser.decode(getBytes(file));
        String name = file.getOriginalFilename() == null ? ""
                : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String text = name.endsWith(".ass")
                ? SubtitleParser.parseAss(content)
                : SubtitleParser.parseSrt(content);
        if (text.isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "字幕文件没有可识别文本");
        }
        return createTextTask(text, template);
    }

    private byte[] getBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new ApiException(400, "INVALID_PARAM", "字幕文件读取失败");
        }
    }

    private TaskCreatedResponse createTextTask(String rawText, String explicitTemplate) {
        return createTextTask(rawText, explicitTemplate, null);
    }

    /** T4.99/C2：language 可选（校验与上传共用 TaskService.normalizeLanguage；null=部署级）。 */
    private TaskCreatedResponse createTextTask(String rawText, String explicitTemplate, String language) {
        String lang = TaskService.normalizeLanguage(language);
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM", "text 不能为空");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ApiException(400, "INVALID_PARAM", "text 不能超过 " + MAX_TEXT_CHARS + " 字符");
        }
        // T3.9：模板解析（文本/字幕不做文件名推荐：显式值否则 meeting；非法显式值 400 不静默回落）
        TemplateResolver.Resolution resolution = TemplateResolver.resolveText(explicitTemplate);
        log.info("模板：{}（来源：{}）", resolution.template(), resolution.source());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(bytes);
        Long taskId = transactionTemplate.execute(tx -> {
            Long newId = jdbc.query("""
                            INSERT INTO task (user_id, source_type, content_hash, duration_sec, size_bytes, status, request_id, language)
                            VALUES (?, 'PASTE', ?, NULL, ?, 'PENDING', ?, ?)
                            ON CONFLICT (user_id, content_hash) DO NOTHING
                            RETURNING id
                            """,
                    rs -> rs.next() ? rs.getLong(1) : null,
                    UserContext.current(), sha256, (long) bytes.length,
                    org.slf4j.MDC.get("requestId"), lang); // T4.86/P-26 同前；T4.99：任务级语言
            if (newId == null) {
                return null;
            }
            // T3.9：提交事务内先写模板行（零迁移）；worker/结构化只更新各自列，不会覆盖成 NULL。
            // 幂等命中路径（newId == null）不写——旧任务保留自己的模板（F-17：幂等键不含 template）。
            jdbc.update("INSERT INTO task_result (task_id, template) VALUES (?, ?)",
                    newId, resolution.template());
            quotaService.consume(UserContext.current(), 0);
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
            TextTaskService.ExistingTask existing = findIdAndStatusByHash(UserContext.current(), sha256);
            if (existing == null) {
                throw new ApiException(500, "INTERNAL_ERROR", "幂等冲突回查未找到已有任务，请联系管理员");
            }
            if (existing.trashed()) {
                // T4.96/D-245：幂等命中回收站任务 → 自动恢复并复用（与 TaskService/LinkTaskService 同口径）
                jdbc.update("UPDATE task SET deleted_at = NULL, updated_at = now() WHERE id = ? AND deleted_at IS NOT NULL",
                        existing.id());
                log.info("文本任务 {} 幂等命中回收站任务，已自动恢复（D-245）", existing.id());
            }
            if ("DEAD".equals(existing.status())) {
                // T4.60 B2：复活两连 + 重投收敛到 TaskRequeueSupport
                taskRequeueSupport.reviveDeadThenPublish(existing.id());
                log.info("文本任务 {} 已 DEAD，重复提交触发复活重投（D-049）", existing.id());
                return new TaskCreatedResponse(existing.id(), "PENDING", sha256, null, bytes.length, true);
            }
            if ("PENDING".equals(existing.status())) {
                // T4.52 B1 自愈：Redis 故障期间留下的 PENDING——重提即重新入队（与 TaskService 同口径）
                taskRequeueSupport.publishPending(existing.id(),
                        String.format("文本任务 %d 重提命中 PENDING，重新入队（T4.52 自愈）", existing.id()));
            }
            return new TaskCreatedResponse(existing.id(), existing.status(), sha256, null, bytes.length, true);
        }
        queuePublisher.publish(taskId);
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

    record ExistingTask(long id, String status, boolean trashed) {
    }

    private ExistingTask findIdAndStatusByHash(long userId, String sha256) {
        return jdbc.query(
                "SELECT id, status, (deleted_at IS NOT NULL) AS trashed FROM task WHERE content_hash = ? AND user_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? new ExistingTask(rs.getLong(1), rs.getString(2), rs.getBoolean(3)) : null,
                sha256, userId);
    }
}
