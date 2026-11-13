package com.meetingnotes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S3（MinIO 兼容）客户端：endpoint / key / path-style 全走环境变量（spec N11）。
 * 启动时尽力确保 bucket 存在，首次 up 无需手工建桶。
 */
@Configuration
public class S3StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(S3StorageConfig.class);

    @Bean
    public S3Client s3Client(@Value("${app.storage.endpoint}") String endpoint,
                             @Value("${app.storage.access-key}") String accessKey,
                             @Value("${app.storage.secret-key}") String secretKey,
                             @Value("${app.storage.region}") String region,
                             @Value("${app.storage.path-style}") boolean pathStyle) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    @Bean
    public ApplicationRunner ensureBucket(S3Client s3, @Value("${app.storage.bucket}") String bucket) {
        // 只做"尽力确认"：MinIO 未就绪（如无 key 启动测试、单测上下文）绝不阻断应用启动（D-035/N15），
        // 真正的兜底在 TaskService：首次 putObject 遇 NoSuchBucket 时惰性建桶重试
        return args -> {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (Exception e) {
                log.warn("启动时确认 bucket 失败（对象存储可能未就绪），首次上传时会重试: {}", e.getMessage());
            }
        };
    }
}
