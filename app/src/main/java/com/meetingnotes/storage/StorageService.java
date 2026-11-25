package com.meetingnotes.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存储只读统计与孤儿清理（T4.92/P-22 前半）：
 * <ul>
 *   <li>{@link #diskUsage()}：磁盘总量/可用/已用百分比（FileStore）；</li>
 *   <li>{@link #audioUsage()}：桶内 {@code tasks/} 前缀的对象数与字节数（分页遍历，
 *       上限 {@value #SCAN_LIMIT} 个对象，超过即 truncated=true，防大桶拖死接口）；</li>
 *   <li>{@link #orphans()}：桶内 tasks/ 键 − DB {@code source_uri} 引用 = 孤儿
 *       （SQL 只拉 source_uri 列、只取 s3:// 前缀行，禁止全表拉）；</li>
 *   <li>{@link #cleanup(boolean)}：dryRun 只统计给样例（≤5）；false 才真删孤儿
 *       （逐个 deleteObject；被引用对象一律计入 skipped）。</li>
 * </ul>
 * 所有 S3/DB 异常捕获 → 统计接口返回 available=false 或空结果，绝不 500（卡面 C3）。
 * 不在启动时扫桶、不加 @Scheduled（禁止项 3/4）。
 */
@Component
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    static final String PREFIX = "tasks/";
    static final int SCAN_LIMIT = 20_000;
    private static final long MB = 1024L * 1024;

    private final S3Client s3;
    private final JdbcTemplate jdbc;
    private final String bucket;
    private final Path probeDir;

    public StorageService(S3Client s3, JdbcTemplate jdbc,
                          @Value("${app.storage.bucket}") String bucket) {
        this.s3 = s3;
        this.jdbc = jdbc;
        this.bucket = bucket;
        String env = System.getenv("STORAGE_PROBE_DIR");
        this.probeDir = Path.of(env == null || env.isBlank() ? System.getProperty("java.io.tmpdir") : env);
    }

    public record Disk(long totalBytes, long freeBytes, int usedPct) {}

    public record Audio(long objects, long bytes, boolean truncated, boolean available, String reason) {}

    public record Orphans(long objects, long bytes, List<String> keys) {}

    public record Cleanup(boolean dryRun, long deletedObjects, long deletedBytes, long skipped, List<String> sample) {}

    private record Scan(Map<String, Long> objects, boolean truncated) {}

    /** 磁盘占用；探测失败返回全 0（前端展示为不可用，不抛）。 */
    public Disk diskUsage() {
        try {
            FileStore store = Files.getFileStore(probeDir);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            return new Disk(total, free, total > 0 ? (int) ((total - free) * 100 / total) : 0);
        } catch (Exception e) {
            log.warn("磁盘占用统计失败（probe={}）：{}", probeDir, e.getMessage());
            return new Disk(0, 0, 0);
        }
    }

    /** 音频占用（tasks/ 前缀，≤20000 对象）；S3 异常 → available=false 带原因。 */
    public Audio audioUsage() {
        try {
            Scan scan = scanBucket();
            long bytes = scan.objects().values().stream().mapToLong(Long::longValue).sum();
            return new Audio(scan.objects().size(), bytes, scan.truncated(), true, null);
        } catch (RuntimeException e) {
            log.warn("音频占用统计失败：{}", e.getMessage());
            return new Audio(0, 0, false, false, e.getMessage());
        }
    }

    /** 孤儿对象（桶内 tasks/ − DB source_uri 引用）；S3/DB 异常 → 空结果。 */
    public Orphans orphans() {
        try {
            Scan scan = scanBucket();
            Set<String> referenced = referencedKeys();
            List<String> keys = new ArrayList<>();
            long bytes = 0;
            for (Map.Entry<String, Long> e : scan.objects().entrySet()) {
                if (!referenced.contains(e.getKey())) {
                    keys.add(e.getKey());
                    bytes += e.getValue();
                }
            }
            return new Orphans(keys.size(), bytes, keys);
        } catch (RuntimeException e) {
            log.warn("孤儿对象统计失败：{}", e.getMessage());
            return new Orphans(0, 0, List.of());
        }
    }

    /** 孤儿清理：dryRun=true 只统计给样例（≤5 个 key）；false 才真删；被引用对象一律 skipped。 */
    public Cleanup cleanup(boolean dryRun) {
        try {
            Scan scan = scanBucket();
            Set<String> referenced = referencedKeys();
            long deletedObjects = 0;
            long deletedBytes = 0;
            long skipped = 0;
            List<String> sample = new ArrayList<>();
            for (Map.Entry<String, Long> e : scan.objects().entrySet()) {
                if (referenced.contains(e.getKey())) {
                    skipped++;
                    continue;
                }
                if (!dryRun) {
                    s3.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                            .bucket(bucket).key(e.getKey()).build());
                }
                deletedObjects++;
                deletedBytes += e.getValue();
                if (sample.size() < 5) {
                    sample.add(e.getKey());
                }
            }
            if (!dryRun && deletedObjects > 0) {
                log.info("孤儿对象清理：删除 {} 个 / {} MB（前 5 个 key：{}）",
                        deletedObjects, deletedBytes / MB, sample);
            }
            return new Cleanup(dryRun, deletedObjects, deletedBytes, skipped, sample);
        } catch (RuntimeException e) {
            log.warn("孤儿清理失败（dryRun={}）：{}", dryRun, e.getMessage());
            return new Cleanup(dryRun, 0, 0, 0, List.of());
        }
    }

    /** 分页扫桶（tasks/ 前缀，≤20000 对象即截断）。 */
    private Scan scanBucket() {
        Map<String, Long> objects = new LinkedHashMap<>();
        boolean truncated = false;
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(PREFIX).build();
        ListObjectsV2Response page = s3.listObjectsV2(request);
        while (true) {
            for (S3Object o : page.contents()) {
                objects.put(o.key(), o.size());
                if (objects.size() >= SCAN_LIMIT) {
                    return new Scan(objects, true);
                }
            }
            Boolean more = page.isTruncated(); // 空安全：SDK 返回 Boolean，缺省视为无后续页
            String token = page.nextContinuationToken();
            if (!Boolean.TRUE.equals(more) || token == null || token.isBlank()) {
                break;
            }
            page = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(PREFIX)
                    .continuationToken(token).build());
        }
        return new Scan(objects, truncated);
    }

    /** DB 引用的完整 source_uri 集合（只拉 source_uri 列，只取 s3:// 前缀行）。 */
    private Set<String> referencedKeys() {
        Set<String> uris = new HashSet<>(jdbc.queryForList(
                "SELECT source_uri FROM task WHERE source_uri LIKE 's3://%'", String.class));
        Set<String> keys = new HashSet<>();
        for (String uri : uris) {
            keys.add(uri.substring(("s3://" + bucket + "/").length()));
        }
        return keys;
    }
}
