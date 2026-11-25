package com.meetingnotes.web;

import com.meetingnotes.common.UserContext;
import com.meetingnotes.storage.StorageGuard;
import com.meetingnotes.storage.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储占用与孤儿清理（T4.92/P-22 前半，仅管理员）：权限口径照 {@link AdminUserController}
 * （{@code AuthService.requireAdmin}——非 ADMIN 403 FORBIDDEN，未认证 401 由 ApiTokenFilter 横切）。
 * 键名 snake_case（D-041）：disk{total_bytes,free_bytes,used_pct} / audio{objects,bytes,truncated,available} /
 * orphans{objects,bytes} / thresholds{min_free_mb,headroom_mb}；
 * cleanup 默认 dryRun=true（只统计给样例 ≤5），dryRun=false 才真删（只删「tasks/ 前缀 + 无引用」对象）。
 */
@RestController
@RequestMapping("/api/v1/admin/storage")
public class AdminStorageController {

    private final StorageService storageService;
    private final StorageGuard storageGuard;
    private final AuthService authService;

    public AdminStorageController(StorageService storageService, StorageGuard storageGuard,
                                  AuthService authService) {
        this.storageService = storageService;
        this.storageGuard = storageGuard;
        this.authService = authService;
    }

    /** 占用总览：磁盘 / 音频（含 truncated 与 available）/ 孤儿 / 阈值。 */
    @GetMapping
    @ResponseBody
    public Map<String, Object> overview() {
        authService.requireAdmin(UserContext.current());
        StorageService.Disk disk = storageService.diskUsage();
        StorageService.Audio audioRec = storageService.audioUsage();
        StorageService.Orphans orphans = storageService.orphans();

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("objects", audioRec.objects());
        audio.put("bytes", audioRec.bytes());
        audio.put("truncated", audioRec.truncated());
        audio.put("available", audioRec.available());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disk", Map.of("total_bytes", disk.totalBytes(),
                "free_bytes", disk.freeBytes(), "used_pct", disk.usedPct()));
        body.put("audio", audio);
        body.put("orphans", Map.of("objects", orphans.objects(), "bytes", orphans.bytes()));
        body.put("thresholds", Map.of("min_free_mb", storageGuard.minFreeMb(),
                "headroom_mb", storageGuard.headroomMb()));
        return body;
    }

    /** 孤儿清理：默认 dryRun=true（只统计给样例 ≤5）；dryRun=false 才真删。 */
    @PostMapping("/cleanup")
    @ResponseBody
    public Map<String, Object> cleanup(@RequestParam(defaultValue = "true") boolean dryRun) {
        authService.requireAdmin(UserContext.current());
        StorageService.Cleanup result = storageService.cleanup(dryRun);
        return Map.of("dry_run", dryRun,
                "deleted_objects", result.deletedObjects(),
                "deleted_bytes", result.deletedBytes(),
                "skipped", result.skipped(),
                "sample", result.sample());
    }
}
