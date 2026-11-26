package com.meetingnotes.web;

import com.meetingnotes.storage.StorageGuard;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4.93 C4：SetupChecks 五项自检——ok=false 时文案逐字（卡面 C4）、detail 不含 URL/IP/端口/凭据、
 * 任何一项失败不抛异常。postgres 用真 H2 验通过态；redis/minio 用 mock 验两态；
 * asr 用不可达端口验失败兜底（真通过态在隔离栈 A1/A2 覆盖）；disk 用真 StorageGuard 两态。
 */
class SetupChecksTest {

    private static final String BUCKET = "meetingnotes";

    private SetupChecks newChecks(DataSource ds, StringRedisTemplate redis, S3Client s3,
                                  StorageGuard guard, String asrBaseUrl, String asrModel) {
        // JdbcTemplate 构造器拒绝 null DataSource（afterPropertiesSet）——不测 postgres 的用例给 mock
        DataSource safeDs = ds != null ? ds : mock(DataSource.class);
        return new SetupChecks(safeDs, redis, s3, BUCKET, guard, asrBaseUrl, asrModel);
    }

    /* ---- postgres ---- */

    @Test
    void postgresOkWithRealH2() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:t493setup;MODE=PostgreSQL", "sa", "");
        SetupChecks.Check c = newChecks(ds, null, null, null, "http://127.0.0.1:1", "small").postgres();
        assertTrue(c.ok(), "H2 SELECT 1 必须通过");
        assertEquals("postgres", c.key());
        assertEquals("已连接", c.detail());
    }

    @Test
    void postgresFailureGivesVerbatimHintWithoutLeaking() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new RuntimeException("jdbc:postgresql://10.0.0.5:5432/secret"));
        SetupChecks.Check c = newChecks(ds, null, null, null, "http://127.0.0.1:1", "small").postgres();
        assertFalse(c.ok());
        assertEquals("数据库不可用：docker logs meeting-notes-postgres-1", c.detail());
        assertFalse(c.detail().contains("10.0.0.5"), "detail 禁止泄漏地址（T4.89 同类面）");
    }

    /* ---- redis ---- */

    @Test
    void redisOkReportsPingMillis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn("PONG");
        SetupChecks.Check c = newChecks(null, redis, null, null, "http://127.0.0.1:1", "small").redis();
        assertTrue(c.ok());
        assertTrue(c.detail().matches("PING \\d+ ms"), "detail 必须是「PING N ms」：" + c.detail());
    }

    @Test
    void redisFailureGivesVerbatimHint() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenThrow(new RuntimeException("connection refused http://redis:6379"));
        SetupChecks.Check c = newChecks(null, redis, null, null, "http://127.0.0.1:1", "small").redis();
        assertFalse(c.ok());
        assertEquals("Redis 不可用：docker logs meeting-notes-redis-1（任务队列会暂停投递）", c.detail());
        assertFalse(c.detail().contains("redis:6379"));
    }

    /* ---- minio ---- */

    @Test
    void minioOkReportsBucket() {
        S3Client s3 = mock(S3Client.class);
        SetupChecks.Check c = newChecks(null, null, s3, null, "http://127.0.0.1:1", "small").minio();
        assertTrue(c.ok());
        assertEquals("桶 " + BUCKET, c.detail());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void minioFailureGivesVerbatimHint() {
        S3Client s3 = mock(S3Client.class);
        // SetupChecks 用的是 Consumer 重载（r -> r.bucket(...)），桩必须打同一个重载
        when(s3.headBucket(any(java.util.function.Consumer.class)))
                .thenThrow(new RuntimeException("http://minio:9000 denied"));
        SetupChecks.Check c = newChecks(null, null, s3, null, "http://127.0.0.1:1", "small").minio();
        assertFalse(c.ok());
        assertEquals("对象存储不可用：检查 .env 的 S3_ENDPOINT/S3_ACCESS_KEY/S3_SECRET_KEY", c.detail());
        assertFalse(c.detail().contains("minio:9000"));
    }

    /* ---- asr ---- */

    @Test
    void asrUnreachableDoesNotThrowAndGivesVerbatimHint() {
        // 127.0.0.1:1 连接立即被拒——必须兜成 ok=false + 逐字文案，绝不抛异常
        SetupChecks.Check c = newChecks(null, null, null, null, "http://127.0.0.1:1", "small").asr();
        assertFalse(c.ok());
        assertEquals("模型未加载：Windows 跑 scripts\\download-model.ps1，Linux/macOS 跑 scripts/first-run.sh，"
                + "再 docker logs -f meeting-notes-asr-1 看原因", c.detail());
    }

    @Test
    void asrModelStatusUnreachableReturnsReadyFalseWithReason() {
        var status = newChecks(null, null, null, null, "http://127.0.0.1:1", "small").asrModelStatus();
        assertEquals(false, status.get("ready"));
        assertEquals("small", status.get("model"));
        assertEquals("ASR /health 不可达", status.get("reason"));
    }

    /* ---- disk ---- */

    @Test
    void diskOkWhenAboveThreshold() {
        StorageGuard guard = new StorageGuard(1, 1); // 下限 1MB——现实磁盘必过
        SetupChecks.Check c = newChecks(null, null, null, guard, "http://127.0.0.1:1", "small").disk();
        assertTrue(c.ok());
        assertTrue(c.detail().matches("剩余 [\\d.]+ GB（下限 1 MB）"), c.detail());
    }

    @Test
    void diskFailureGivesVerbatimHint() {
        StorageGuard guard = new StorageGuard(10_000_000, 256); // 下限 ~10TB → 必红
        SetupChecks.Check c = newChecks(null, null, null, guard, "http://127.0.0.1:1", "small").disk();
        assertFalse(c.ok());
        assertTrue(c.detail().startsWith("磁盘空间不足：剩余 "), c.detail());
        assertTrue(c.detail().endsWith("（可在设置页清理孤儿对象）"), c.detail());
    }

    /* ---- runAll ---- */

    @Test
    void runAllReturnsExactlyFiveInCardOrderAndNeverThrows() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:t493setup2;MODE=PostgreSQL", "sa", "");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn("PONG");
        S3Client s3 = mock(S3Client.class);
        StorageGuard guard = new StorageGuard(1, 1);
        List<SetupChecks.Check> checks = newChecks(ds, redis, s3, guard, "http://127.0.0.1:1", "small").runAll();
        assertEquals(5, checks.size());
        assertEquals(List.of("postgres", "redis", "minio", "asr", "disk"),
                checks.stream().map(SetupChecks.Check::key).toList(), "顺序必须与卡面 C3 示例一致");
    }
}
