package com.meetingnotes.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** T2.4：MinIO 探活——headBucket + 2s API 级超时；成功=UP，异常=DOWN（不抛出）。 */
@ExtendWith(MockitoExtension.class)
class MinioHealthIndicatorTest {

    @Mock
    private S3Client s3;

    @Test
    void bucketReachableReportsUp() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        Health health = new MinioHealthIndicator(s3, "meetingnotes").health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("meetingnotes", health.getDetails().get("bucket"));
    }

    @Test
    void bucketUnreachableReportsDownWithoutThrowing() {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.builder()
                        .message("probe timeout").build());

        Health health = new MinioHealthIndicator(s3, "meetingnotes").health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().containsKey("error"), "DOWN 时应带异常详情供运维排查");
    }
}
