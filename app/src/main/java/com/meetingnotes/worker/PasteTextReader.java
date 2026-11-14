package com.meetingnotes.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * PASTE 文本对象读取（T3.7，从 worker 抽出的独立小类便于 mock）：
 * 按 key 读 MinIO 对象 → UTF-8 字符串，不落临时文件、不碰 ffmpeg。
 */
@Component
public class PasteTextReader {

    private final S3Client s3;
    private final String bucket;

    public PasteTextReader(S3Client s3, @Value("${app.storage.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String read(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asUtf8String();
    }
}
