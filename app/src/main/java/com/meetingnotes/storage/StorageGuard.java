package com.meetingnotes.storage;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储护栏（T4.92/P-21 前半）：启动时横幅告警 + 上传前置断言，防「任务一个个莫名失败，其实是磁盘满」。
 * <ul>
 *   <li>启动（{@link #run}）：可用空间低于 {@code app.storage.min-free-mb}（默认 2048MB）→
 *       只打一条 ERROR 横幅（含剩余/下限与一句人话建议），<b>不拒绝启动</b>——小盘自部署也要能起来看页面；</li>
 *   <li>上传前置（{@link #assertWritable}）：低于 {@code app.storage.upload-headroom-mb}（默认 256MB）→
 *       抛 503 {@code INSUFFICIENT_STORAGE}（message 逐字按卡面），UPLOAD/LINK 入口调用，PASTE 不落盘不受限；</li>
 *   <li>探测目录：环境变量 {@code STORAGE_PROBE_DIR}，默认 {@code java.io.tmpdir}；用
 *       {@link Files#getFileStore(Path)} 的 usable space（按该路径所在文件系统）。</li>
 * </ul>
 * 误配（≤0）→ 启动 WARN 回退默认（照 WeakApiTokenGuard 口径）；探测失败 → 视为空间充足放行
 * （护栏误伤比漏报更伤自部署体验），仅 WARN 一次。
 */
@Component
public class StorageGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageGuard.class);
    private static final long MB = 1024L * 1024;

    private final long minFreeBytes;
    private final long headroomBytes;
    private final Path probeDir;

    public StorageGuard(@Value("${app.storage.min-free-mb:2048}") long minFreeMb,
                        @Value("${app.storage.upload-headroom-mb:256}") long headroomMb) {
        long min = minFreeMb;
        long head = headroomMb;
        if (min <= 0 || head <= 0) {
            log.warn("app.storage.min-free-mb/upload-headroom-mb 误配（{}/{}，必须 > 0），回退默认 2048/256",
                    minFreeMb, headroomMb);
            min = min <= 0 ? 2048 : min;
            head = head <= 0 ? 256 : head;
        }
        this.minFreeBytes = min * MB;
        this.headroomBytes = head * MB;
        String env = System.getenv("STORAGE_PROBE_DIR");
        this.probeDir = Path.of(env == null || env.isBlank() ? System.getProperty("java.io.tmpdir") : env);
    }

    @Override
    public void run(ApplicationArguments args) {
        long free = usableBytes();
        if (free < minFreeBytes) {
            log.error("【存储护栏】磁盘可用空间不足：剩余 {} MB，低于下限 {} MB。"
                    + "转写音频会持续占用磁盘（约 2.4 MB/任务），请清理磁盘或删除不需要的任务后再上传新任务。",
                    free / MB, minFreeBytes / MB);
        }
    }

    /** 可用字节数；探测失败返回 Long.MAX_VALUE（视为充足——护栏不误伤，仅 WARN）。 */
    public long usableBytes() {
        try {
            FileStore store = Files.getFileStore(probeDir);
            return store.getUsableSpace();
        } catch (Exception e) {
            log.warn("磁盘可用空间探测失败（probe={}），本次护栏放行：{}", probeDir, e.getMessage());
            return Long.MAX_VALUE;
        }
    }

    /** 上传前置断言：低于 headroom → 503 INSUFFICIENT_STORAGE（可重试）。 */
    public void assertWritable() {
        long free = usableBytes();
        if (free < headroomBytes) {
            throw new ApiException(503, "INSUFFICIENT_STORAGE", "磁盘可用空间不足（剩余 "
                    + free / MB + " MB，至少需要 " + headroomBytes / MB + " MB），请清理后再上传");
        }
    }

    public long minFreeMb() {
        return minFreeBytes / MB;
    }

    public long headroomMb() {
        return headroomBytes / MB;
    }
}
