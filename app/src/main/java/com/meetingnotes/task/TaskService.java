package com.meetingnotes.task;

import com.meetingnotes.upload.AudioDurationProbe;
import com.meetingnotes.upload.MediaMagic;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.storage.StorageGuard;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.common.TaskCreatedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final int HEAD_BYTES = 16;

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final AudioDurationProbe probe;
    private final QuotaService quotaService;
    private final QueuePublisher queuePublisher;
    private final TaskRequeueSupport taskRequeueSupport;
    private final TransactionTemplate transactionTemplate;
    private final long maxSizeBytes;
    private final long maxDurationSec;
    private final String bucket;
    private final StorageGuard storageGuard;

    public TaskService(JdbcTemplate jdbc,
                       S3Client s3,
                       AudioDurationProbe probe,
                       QuotaService quotaService,
                       QueuePublisher queuePublisher,
                       TaskRequeueSupport taskRequeueSupport,
                       TransactionTemplate transactionTemplate,
                       @Value("${app.task.max-size-bytes}") long maxSizeBytes,
                       @Value("${app.task.max-duration-sec}") long maxDurationSec,
                       @Value("${app.storage.bucket}") String bucket,
                       StorageGuard storageGuard) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.probe = probe;
        this.quotaService = quotaService;
        this.queuePublisher = queuePublisher;
        this.taskRequeueSupport = taskRequeueSupport;
        this.transactionTemplate = transactionTemplate;
        this.maxSizeBytes = maxSizeBytes;
        this.maxDurationSec = maxDurationSec;
        this.bucket = bucket;
        this.storageGuard = storageGuard;
    }

    public TaskCreatedResponse createUpload(MultipartFile file) throws IOException {
        return createUpload(file, null, null);
    }

    public TaskCreatedResponse createUpload(MultipartFile file, String template) throws IOException {
        return createUpload(file, template, null);
    }

    /** T4.99/C2 校验规则（三入口共用）：NULL/空白 → null（用部署级 ASR_LANGUAGE）；auto 或 ^[a-z]{2,3}$ 通过；
     *  其余 → 400 INVALID_PARAM 文案逐字（zh/en/ja 仅为示例，不是白名单）。 */
    public static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String v = language.trim();
        if ("auto".equals(v) || v.matches("^[a-z]{2,3}$")) {
            return v;
        }
        throw new ApiException(400, "INVALID_PARAM", "language 只支持 2–3 位小写语言码（如 zh、en、ja），或 auto");
    }

    /** 音频上传（T3.9 起带可选 template；T4.99 起带可选 language：null=部署级，校验见 normalizeLanguage）。 */
    public TaskCreatedResponse createUpload(MultipartFile file, String template, String language) throws IOException {
        String lang = normalizeLanguage(language);
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "INVALID_MEDIA", "multipart 字段 file 缺失或为空文件");
        }
        storageGuard.assertWritable(); // T4.92/P-21：落盘前磁盘护栏（低于 headroom → 503 INSUFFICIENT_STORAGE）

        // T3.9：模板解析先行（显式优先 → 文件名推荐 → 默认；非法显式值 400，先于一切副作用）
        TemplateResolver.Resolution resolution = TemplateResolver.resolve(template, file.getOriginalFilename());
        log.info("模板：{}（来源：{}）", resolution.template(), resolution.source());

        // 1) magic number：读前 16 字节判断，不落盘、不信扩展名/MIME
        byte[] head = new byte[HEAD_BYTES];
        int headLen;
        try (InputStream in = file.getInputStream()) {
            headLen = in.readNBytes(head, 0, head.length);
        }
        String format = MediaMagic.detect(java.util.Arrays.copyOf(head, headLen));
        if (format == null) {
            // T3.10：失败提示补 spec §4 验证命令要求的 mp3 转码兜底指引（只改文案，错误码不变）
            throw new ApiException(400, "INVALID_MEDIA",
                    "文件头无法识别为受支持的音/视频格式（支持清单见 spec §4，扩展名与 MIME 不作为依据）；"
                            + "若仍失败，请先把音频转成 mp3 再上传");
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

            // 4) 幂等（N1/N10 / T2.1 / D-049）：直接 INSERT，靠 (user_id, content_hash) 唯一约束裁决，
            //    不做"先 SELECT 再 INSERT"（TOCTOU：并发同内容请求只允许建 1 个任务）。
            //    ON CONFLICT DO NOTHING 返回空 → 冲突 → 回查已有任务；
            //    整段包在事务里：配额 429 / MinIO 失败时 task 行随事务回滚，不留孤儿 PENDING。
            Long taskId = transactionTemplate.execute(tx -> {
                Long newId = jdbc.query(
                        """
                        INSERT INTO task (user_id, source_type, content_hash, duration_sec, size_bytes, status, request_id, language)
                        VALUES (?, 'UPLOAD', ?, ?, ?, 'PENDING', ?, ?)
                        ON CONFLICT (user_id, content_hash) DO NOTHING
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getLong(1) : null,
                        UserContext.current(), sha256, durationSec, size,
                        org.slf4j.MDC.get("requestId"), lang); // T4.86/P-26 同前；T4.99：任务级语言（null=部署级）
                if (newId == null) {
                    return null; // 冲突：同用户已有同内容任务，回查交给事务外（事务内不能留未决状态）
                }
                // T3.9：提交事务内先写模板行（零迁移：task_result.template 列已存在）。worker 的
                // saveTranscript 与结构化 persist 只更新各自列，不会把它覆盖成 NULL；幂等命中路径
                // （newId == null）不写——旧任务保留自己的模板。
                jdbc.update("INSERT INTO task_result (task_id, template) VALUES (?, ?)",
                        newId, resolution.template());
                // 5) 配额（N8）：只在"本请求确实新建了任务"时消费；429 抛出 → 事务回滚
                int minutes = (int) Math.max(1, Math.ceil(durationSec / 60.0));
                quotaService.consume(UserContext.current(), minutes);
                // 6) MinIO（tasks/{taskId}/）→ 回填 source_uri；失败 → 事务回滚，不留无对象的任务
                String key = "tasks/" + newId + "/" + sanitizeFilename(file.getOriginalFilename(), format);
                try {
                    putObjectEnsuringBucket(key, tmp);
                } catch (software.amazon.awssdk.core.exception.SdkException e) {
                    // T2.11 F-2：存储故障映射为稳定错误码，接入方才能按码分支（不外泄内部细节）
                    log.error("任务 {} 写入对象存储失败", newId, e);
                    throw new ApiException(500, "STORAGE_ERROR", "对象存储写入失败，请检查 MinIO 服务是否可用");
                }
                jdbc.update("UPDATE task SET source_uri = ?, updated_at = now() WHERE id = ?",
                        "s3://" + bucket + "/" + key, newId);
                return newId;
            });

            if (taskId == null) {
                // 幂等命中（T2.1）：返回库里真实状态；命中 DEAD → 复活重投（D-049）
                ExistingTask existing = findIdAndStatusByHash(UserContext.current(), sha256);
                if (existing == null) {
                    // 理论不可达：约束冲突却查不到行。宁可暴露也不给假 201。
                    throw new ApiException(500, "INTERNAL_ERROR", "幂等冲突回查未找到已有任务，请联系管理员");
                }
                if (existing.trashed()) {
                    // T4.96/D-245：幂等命中回收站任务 → 自动恢复并复用（同一 task_id、reused=true）。
                    // 恢复后按原状态逻辑继续走（DEAD → 复活重投；DONE/PENDING → 原样返回）。
                    jdbc.update("UPDATE task SET deleted_at = NULL, updated_at = now() WHERE id = ? AND deleted_at IS NOT NULL",
                            existing.id());
                    log.info("任务 {} 幂等命中回收站任务，已自动恢复（D-245）", existing.id());
                }
                if ("DEAD".equals(existing.status())) {
                    // T2.11 F-1：复活即清死信，否则死信列表出现"已 DONE 的任务"，再失败还会写第二条。
                    // T4.60 B2：复活两连 + 重投收敛到 TaskRequeueSupport（四套重复 SQL 单一实现）
                    taskRequeueSupport.reviveDeadThenPublish(existing.id());
                    log.info("任务 {} 已 DEAD，重传触发复活重投（D-049）", existing.id());
                    return new TaskCreatedResponse(existing.id(), "PENDING", sha256, durationSec, size, true);
                }
                if ("PENDING".equals(existing.status())) {
                    // T4.50 自愈：Redis 故障期间留下的 PENDING（行已提交、队列未投）——重传即重新入队，
                    // 不再把"客户端重试"堵死在幂等命中上
                    taskRequeueSupport.publishPending(existing.id(),
                            String.format("任务 %d 重传命中 PENDING，重新入队（T4.50 自愈）", existing.id()));
                }
                return new TaskCreatedResponse(existing.id(), existing.status(), sha256, durationSec, size, true);
            }

            // 7) 投递 worker 队列（T1.5；事务已提交，不会产生幽灵任务）。入队失败抛 503（T4.50：不得假成功）：
            //    任务行已提交停在 PENDING——客户端重传走幂等重投自愈，或由 T2.3 启动恢复兜底。
            queuePublisher.publish(taskId);

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

    /** 幂等命中时返回的已有任务摘要：id + 库里真实状态 + 是否在回收站（T4.96）。 */
    public record ExistingTask(long id, String status, boolean trashed) {
    }

    /** 按内容 hash 查已有任务；必须带 user_id（D-040），防止未来跨用户读到他人任务。 */
    private ExistingTask findIdAndStatusByHash(long userId, String sha256) {
        return jdbc.query(
                "SELECT id, status, (deleted_at IS NOT NULL) AS trashed FROM task WHERE content_hash = ? AND user_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? new ExistingTask(rs.getLong(1), rs.getString(2), rs.getBoolean(3)) : null,
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
