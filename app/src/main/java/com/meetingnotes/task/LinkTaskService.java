package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.fetch.MediaFetcher;
import com.meetingnotes.upload.AudioDurationProbe;
import com.meetingnotes.upload.MediaMagic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 链接导入（T4.32a）：POST /api/v1/tasks/link —— 贴 http(s) 音/视频直链建任务。
 *
 * <p>顺序与 {@link TaskService#createUpload} / {@link TextTaskService} 同构的管线复用（卡面逐字）：
 * fetch（UrlGuard 每跳校验 + 大小上限，语义见 {@link MediaFetcher}）→ magic number（N6：只信文件头）→
 * 时长（T4.30b webm 兜底同一份探测实现）→ 幂等（(user_id, content_hash) 唯一约束裁决，冲突不耗配额）→
 * 配额（N8，按抓取到的媒体时长计）→ MinIO → 入队；`source_type='LINK'`（卡面 D-127 逐字；
 * V1 schema 注释预留的是 FETCH——无 CHECK 约束，命名漂移以卡为准，见接入指南 §3.9）。
 *
 * <p>安全：SSRF 拦截（400 URL_BLOCKED）在 fetcher 内逐跳执行；本类不碰 HTTP。模板按 URL 文件名
 * 自动推荐（与上传同口径），解析不出文件名回退 meeting。事务内任一步失败整体回滚，不留孤儿 PENDING。
 */
@Service
public class LinkTaskService {

    private static final Logger log = LoggerFactory.getLogger(LinkTaskService.class);

    private static final int HEAD_BYTES = 16;

    private final JdbcTemplate jdbc;
    private final software.amazon.awssdk.services.s3.S3Client s3;
    private final AudioDurationProbe probe;
    private final QuotaService quotaService;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactionTemplate;
    private final MediaFetcher httpFetcher;
    private final MediaFetcher byofFetcher;
    private final long maxDurationSec;
    private final String bucket;
    private final String queueKey;

    public LinkTaskService(JdbcTemplate jdbc,
                           software.amazon.awssdk.services.s3.S3Client s3,
                           AudioDurationProbe probe,
                           QuotaService quotaService,
                           StringRedisTemplate redis,
                           TransactionTemplate transactionTemplate,
                           @Qualifier("httpMediaFetcher") MediaFetcher httpFetcher,
                           @Qualifier("processMediaFetcher") MediaFetcher byofFetcher,
                           @Value("${app.task.max-duration-sec}") long maxDurationSec,
                           @Value("${app.storage.bucket}") String bucket,
                           @Value("${app.worker.queue-key}") String queueKey) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.probe = probe;
        this.quotaService = quotaService;
        this.redis = redis;
        this.transactionTemplate = transactionTemplate;
        this.httpFetcher = httpFetcher;
        this.byofFetcher = byofFetcher;
        this.maxDurationSec = maxDurationSec;
        this.bucket = bucket;
        this.queueKey = queueKey;
    }

    /**
     * T4.32b：useByof=true 时走 BYOF 外部抓取工具（{@code ProcessMediaFetcher}），
     * 未配置 FETCHER_CMD → 400 FETCHER_UNAVAILABLE（码表只增）；false 走 T4.32a 直链下载，
     * 既有语义与错误码不变。两条路入口都先过 UrlGuard（在各自 fetcher 内逐跳执行）。
     */
    public TaskCreatedResponse createLink(String url, boolean useByof) {
        if (url == null || url.isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "url 不能为空");
        }
        MediaFetcher fetcher = useByof ? byofFetcher : httpFetcher;
        if (useByof && fetcher instanceof com.meetingnotes.fetch.ProcessMediaFetcher pmf
                && !pmf.isConfigured()) {
            // use_byof=true 且未配置 FETCHER_CMD → 400 FETCHER_UNAVAILABLE（新码，码表只增）
            throw new ApiException(400, "FETCHER_UNAVAILABLE",
                    "未配置外部抓取工具（FETCHER_CMD），无法抓取该页面；请把媒体导出成文件后上传（导出文件 → 打开本站 → 上传）");
        }
        Path tmp;
        try {
            tmp = Files.createTempFile("mn-link-", ".bin");
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "创建临时文件失败，请稍后重试");
        }
        try {
            // 1) 抓取：SSRF 每跳校验 / 网页 400 NOT_DIRECT_MEDIA / 超大小 413 / 网络失败 502，契约在 MediaFetcher
            MediaFetcher.FetchResult fetched = fetcher.fetch(url, tmp);
            log.info("直链抓取完成：{} 字节，Content-Type {}", fetched.sizeBytes(), fetched.contentType());

            // 2) magic number（复用上传管线同一道闸，N6：只信文件头，Content-Type/扩展名不作依据）
            String format = MediaMagic.detect(readHead(tmp));
            if (format == null) {
                throw new ApiException(400, "INVALID_MEDIA",
                        "链接内容无法识别为受支持的音/视频格式（只信文件头）；若为网页链接请先把媒体导出成文件再上传");
            }
            long size = Files.size(tmp);

            // 3) 时长（T4.30b webm 兜底同一份探测实现；不可解析 → 400 INVALID_MEDIA 在探测内部抛）
            long durationSec = (long) Math.ceil(probe.durationSeconds(tmp));
            if (durationSec > maxDurationSec) {
                throw new ApiException(400, "DURATION_EXCEEDED",
                        "时长 " + durationSec + " 秒，超过上限 " + maxDurationSec + " 秒");
            }

            // 4) 模板：按 URL 文件名推荐（与音频上传同口径），无文件名回退 meeting
            String filename = filenameFromUrl(url, format);
            TemplateResolver.Resolution resolution = TemplateResolver.resolve(null, filename);
            log.info("链接导入模板：{}（来源：{}）", resolution.template(), resolution.source());

            // 5) 幂等 + 配额 + MinIO（与 TaskService 同构：事务内失败整体回滚，不留孤儿 PENDING）
            String sha256 = sha256OfFile(tmp);
            Long taskId = transactionTemplate.execute(tx -> {
                Long newId = jdbc.query(
                        """
                        INSERT INTO task (user_id, source_type, content_hash, duration_sec, size_bytes, status)
                        VALUES (?, 'LINK', ?, ?, ?, 'PENDING')
                        ON CONFLICT (user_id, content_hash) DO NOTHING
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getLong(1) : null,
                        UserContext.current(), sha256, durationSec, size);
                if (newId == null) {
                    return null; // 冲突：同用户已有同内容任务，回查交给事务外
                }
                jdbc.update("INSERT INTO task_result (task_id, template) VALUES (?, ?)",
                        newId, resolution.template());
                int minutes = (int) Math.max(1, Math.ceil(durationSec / 60.0));
                quotaService.consume(UserContext.current(), minutes);
                String key = "tasks/" + newId + "/" + filename;
                try {
                    s3.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                                    .bucket(bucket).key(key).build(),
                            software.amazon.awssdk.core.sync.RequestBody.fromFile(tmp));
                } catch (software.amazon.awssdk.core.exception.SdkException e) {
                    log.error("任务 {} 写入对象存储失败", newId, e);
                    throw new ApiException(500, "STORAGE_ERROR", "对象存储写入失败，请检查 MinIO 服务是否可用");
                }
                jdbc.update("UPDATE task SET source_uri = ?, updated_at = now() WHERE id = ?",
                        "s3://" + bucket + "/" + key, newId);
                return newId;
            });

            if (taskId == null) {
                // 幂等命中（与上传同口径）：返回库里真实状态；命中 DEAD → 复活重投（D-049）
                ExistingTask existing = findIdAndStatusByHash(UserContext.current(), sha256);
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
                    log.info("链接任务 {} 已 DEAD，同链接重提触发复活重投（D-049）", existing.id());
                    return new TaskCreatedResponse(existing.id(), "PENDING", sha256, durationSec, size, true);
                }
                return new TaskCreatedResponse(existing.id(), existing.status(), sha256, durationSec, size, true);
            }

            enqueue(taskId);
            return new TaskCreatedResponse(taskId, "PENDING", sha256, durationSec, size, false);
        } catch (IOException e) {
            log.error("直链临时文件读写失败", e);
            throw new ApiException(500, "INTERNAL_ERROR", "临时文件读写失败，请稍后重试");
        } catch (UncheckedIOException e) {
            log.error("直链临时文件读写失败", e);
            throw new ApiException(500, "INTERNAL_ERROR", "临时文件读写失败，请稍后重试");
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.warn("直链临时文件未清理：{}（{}）", tmp, e.getMessage());
            }
        }
    }

    /** 只读文件头若干字节做魔数判断，不整文件读进内存。 */
    private static byte[] readHead(Path file) throws IOException {
        byte[] head = new byte[HEAD_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int len = in.readNBytes(head, 0, head.length);
            return java.util.Arrays.copyOf(head, len);
        }
    }

    /** 从 URL 路径取文件名（URL 解码后清洗，防路径穿越）；空/异常名回退 link.<format>。 */
    static String filenameFromUrl(String url, String format) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            path = null;
        }
        String base = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        try {
            base = URLDecoder.decode(base, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 百分号编码不合法就用原始段，不影响建任务
        }
        base = base.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (base.isBlank() || base.startsWith(".")) {
            return "link." + format;
        }
        return base.length() > 128 ? base.substring(base.length() - 128) : base;
    }

    /** 抓取落盘后流式计算 SHA-256（幂等键口径与上传一致：内容字节哈希）。 */
    private static String sha256OfFile(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 投递 worker 队列；入队失败抛 503 QUEUE_UNAVAILABLE（T4.50：不得假成功，与 TaskService 同口径）。 */
    private void enqueue(long taskId) {
        try {
            redis.opsForList().leftPush(queueKey, Long.toString(taskId));
        } catch (DataAccessException e) {
            log.error("任务 {} 入队失败（Redis 不可用），返回 503 QUEUE_UNAVAILABLE", taskId, e);
            throw new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试");
        }
    }

    /** 幂等命中时返回的已有任务摘要：id + 库里真实状态。 */
    record ExistingTask(long id, String status) {
    }

    private ExistingTask findIdAndStatusByHash(long userId, String sha256) {
        return jdbc.query(
                "SELECT id, status FROM task WHERE content_hash = ? AND user_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? new ExistingTask(rs.getLong(1), rs.getString(2)) : null,
                sha256, userId);
    }
}
