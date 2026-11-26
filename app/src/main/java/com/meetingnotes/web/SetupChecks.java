package com.meetingnotes.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.storage.StorageGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * T4.93 C4 首启向导五项自检：postgres / redis / minio / asr / disk。
 * <ul>
 *   <li>每项独立 try/catch，任何一项失败都不抛异常（异常 ⇒ ok=false + 中文修复指引）；</li>
 *   <li>超时：DB 3s（独立 JdbcTemplate queryTimeout）/ Redis 3s（复用 REDIS_TIMEOUT 命令超时）/
 *       MinIO 5s（apiCallTimeout）/ ASR 5s（独立探活工厂）；</li>
 *   <li>detail 只放人类可读结论，禁止 URL/IP/端口/凭据（T4.89 同类泄漏面，设计稿 §3.3）。</li>
 * </ul>
 */
@Component
public class SetupChecks {

    /** 单项检查结果（对外 JSON snake_case 由 Jackson 全局策略处理，字段名本身已是小写）。 */
    public record Check(String key, String label, boolean ok, String detail) {
    }

    private final JdbcTemplate db3s;
    private final StringRedisTemplate redis;
    private final S3Client s3;
    private final String bucket;
    private final StorageGuard storageGuard;
    private final String asrBaseUrl;
    private final String asrModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public SetupChecks(DataSource dataSource,
                       StringRedisTemplate redis,
                       S3Client s3,
                       @Value("${app.storage.bucket}") String bucket,
                       StorageGuard storageGuard,
                       @Value("${spring.ai.openai.audio.transcription.base-url}") String asrBaseUrl,
                       @Value("${spring.ai.openai.audio.transcription.options.model:small}") String asrModel) {
        JdbcTemplate db = new JdbcTemplate(dataSource);
        db.setQueryTimeout(3); // 卡面 C4：DB 3s
        this.db3s = db;
        this.redis = redis;
        this.s3 = s3;
        this.bucket = bucket;
        this.storageGuard = storageGuard;
        this.asrBaseUrl = asrBaseUrl;
        this.asrModel = asrModel;
    }

    /** 全量五项（顺序对齐卡面 C3 示例，判据逐字对齐 C4）。 */
    public List<Check> runAll() {
        return List.of(postgres(), redis(), minio(), asr(), disk());
    }

    Check postgres() {
        try {
            db3s.queryForObject("SELECT 1", Integer.class);
            return new Check("postgres", "数据库", true, "已连接");
        } catch (Exception e) {
            return new Check("postgres", "数据库", false,
                    "数据库不可用：docker logs meeting-notes-postgres-1");
        }
    }

    Check redis() {
        try {
            long start = System.nanoTime();
            // 显式 RedisCallback（execute 对 lambda 有 RedisCallback/SessionCallback 二义）
            redis.execute((org.springframework.data.redis.core.RedisCallback<String>) conn -> conn.ping());
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new Check("redis", "Redis", true, "PING " + ms + " ms");
        } catch (Exception e) {
            return new Check("redis", "Redis", false,
                    "Redis 不可用：docker logs meeting-notes-redis-1（任务队列会暂停投递）");
        }
    }

    Check minio() {
        try {
            s3.headBucket(r -> r.bucket(bucket)
                    .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(5))));
            return new Check("minio", "对象存储", true, "桶 " + bucket);
        } catch (Exception e) {
            return new Check("minio", "对象存储", false,
                    "对象存储不可用：检查 .env 的 S3_ENDPOINT/S3_ACCESS_KEY/S3_SECRET_KEY");
        }
    }

    Check asr() {
        Map<String, Object> status = asrModelStatus();
        boolean ready = Boolean.TRUE.equals(status.get("ready"));
        String model = String.valueOf(status.get("model"));
        if (ready) {
            return new Check("asr", "语音模型", true, model);
        }
        return new Check("asr", "语音模型", false,
                "模型未加载：Windows 跑 scripts\\download-model.ps1，Linux/macOS 跑 scripts/first-run.sh，"
                        + "再 docker logs -f meeting-notes-asr-1 看原因");
    }

    Check disk() {
        long usable = storageGuard.usableBytes();
        long minFreeMb = storageGuard.minFreeMb();
        double gb = usable / (1024.0 * 1024.0 * 1024.0);
        if (usable >= minFreeMb * 1024L * 1024L) {
            return new Check("disk", "磁盘余量", true,
                    String.format("剩余 %.2f GB（下限 %d MB）", gb, minFreeMb));
        }
        return new Check("disk", "磁盘余量", false,
                String.format("磁盘空间不足：剩余 %.2f GB，低于下限 %d MB（可在设置页清理孤儿对象）", gb, minFreeMb));
    }

    /** T4.93 C3：GET /api/v1/setup/model 的数据源——透传 ASR /health 的 model/ready；
     *  不可达/超时（5s）不抛 500：返回 ready=false + reason，model 回退配置值。 */
    public Map<String, Object> asrModelStatus() {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
            String body = org.springframework.web.client.RestClient.builder()
                    .requestFactory(factory).build()
                    .get().uri(asrBaseUrl + "/health").retrieve().body(String.class);
            JsonNode json = mapper.readTree(body == null ? "" : body);
            String model = json.path("model").asText(asrModel);
            if (json.path("ready").asBoolean(false)) {
                return Map.of("model", model, "ready", true);
            }
            return Map.of("model", model, "ready", false, "reason", "模型未就绪");
        } catch (Exception e) {
            return Map.of("model", asrModel, "ready", false, "reason", "ASR /health 不可达");
        }
    }
}
