package com.meetingnotes.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.time.Duration;

/**
 * MinIO 探活（T2.4 / D-059）：headBucket + 2s API 级超时——探活独立于业务调用，
 * 不许拖慢/拖死 /actuator/health。异常在 health() 内兜成 DOWN，不抛到 context。
 * 测试环境（H2、无外部依赖）可用 management.health.minio.enabled=false 关闭（NoKeyStartupTest）。
 */
@Component("minio")
@ConditionalOnProperty(name = "management.health.minio.enabled", havingValue = "true", matchIfMissing = true)
public class MinioHealthIndicator implements HealthIndicator {

    /** D-059：探活超时固定 2s，独立于业务超时。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final S3Client s3;
    private final String bucket;

    public MinioHealthIndicator(S3Client s3,
                                @Value("${app.storage.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket)
                    .overrideConfiguration(o -> o.apiCallTimeout(PROBE_TIMEOUT))
                    .build());
            return Health.up().withDetail("bucket", bucket).build();
        } catch (Exception e) {
            return Health.down().withDetail("bucket", bucket)
                    .withException(e).build();
        }
    }
}
