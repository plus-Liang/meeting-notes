package com.meetingnotes.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.92/P-22：StorageService 统计与清理语义——audioUsage 计数/截断/S3 异常 available=false、
 * orphans = 桶内键 − DB 引用、cleanup dryRun 不删 / 真删只删孤儿 / 引用对象计 skipped。
 * S3Client mock listObjectsV2Paginator（迭代器喂页）；jdbc mock queryForList 喂引用 URI。
 */
class StorageServiceTest {

    private static final String BUCKET = "test-bucket";

    private final S3Client s3 = mock(S3Client.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private StorageService service;

    /** 构造分页响应：contents(keys...)；截断标志由 isTruncated 控制。 */
    private ListObjectsV2Response page(boolean truncated, String... keysAndSizes) {
        var builder = ListObjectsV2Response.builder().isTruncated(truncated);
        var contents = new java.util.ArrayList<S3Object>();
        for (int i = 0; i < keysAndSizes.length; i += 2) {
            contents.add(S3Object.builder().key(keysAndSizes[i]).size(Long.parseLong(keysAndSizes[i + 1])).build());
        }
        builder.contents(contents); // contents(List) 是整表设置——必须一次传入（循环逐次调用会覆盖）
        if (truncated) {
            builder.nextContinuationToken("tok");
        }
        return builder.build();
    }

    /** stub 手动分页：scanBucket 每次 listObjectsV2 依次返回各页（末页必须 truncated=false 以终止循环）。 */
    private void stubPages(List<ListObjectsV2Response> pages) {
        assert !pages.get(pages.size() - 1).isTruncated() : "末页必须 truncated=false";
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(pages.get(0),
                pages.subList(1, pages.size()).toArray(new ListObjectsV2Response[0]));
    }

    private void stubReferenced(String... uris) {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers
                .contains("SELECT source_uri FROM task WHERE source_uri LIKE 's3://%'"),
                org.mockito.ArgumentMatchers.eq(String.class))).thenReturn(List.of(uris));
    }

    private StorageService newService() {
        service = new StorageService(s3, jdbc, BUCKET);
        return service;
    }

    /** audioUsage：两页分页累计（对象数/字节）；未到上限 truncated=false。 */
    @Test
    void audioUsageAggregatesAcrossPages() {
        stubPages(List.of(page(true, "tasks/1/a.wav", "100", "tasks/2/b.wav", "200"),
                page(false, "tasks/3/c.wav", "50")));
        StorageService.Audio audio = newService().audioUsage();
        assertEquals(3L, audio.objects());
        assertEquals(350L, audio.bytes());
        assertFalse(audio.truncated());
        assertTrue(audio.available());
    }

    /** 20000 上限：page contents 超限即截断（用 1 页大量对象模拟——这里以截断标志直验语义）。 */
    @Test
    void audioUsageMarksTruncatedWhenLimitReached() {
        // 用多页累计到上限的方式成本高；直接验证「第 20000 个对象后停止」的分支：
        // 构造一页 20001 个对象（mock 内循环构造），断言 objects==20000 且 truncated=true。
        var builder = ListObjectsV2Response.builder().isTruncated(false);
        var contentList = new java.util.ArrayList<S3Object>();
        for (int i = 0; i < 20_001; i++) {
            contentList.add(S3Object.builder().key("tasks/x/" + i).size(1L).build());
        }
        builder.contents(contentList); // SDK 的 varargs setter 是整表设置——必须收集后一次传入（K-212 候选）
        stubPages(List.of(builder.build()));
        StorageService.Audio audio = newService().audioUsage();
        assertEquals(20_000L, audio.objects(), "达到 SCAN_LIMIT 必须停止遍历");
        assertTrue(audio.truncated());
    }

    /** S3 异常 → available=false 带原因，绝不 500。 */
    @Test
    void s3FailureYieldsAvailableFalse() {
        doThrow(new RuntimeException("Connection refused")).when(s3)
                .listObjectsV2(any(ListObjectsV2Request.class));
        StorageService.Audio audio = newService().audioUsage();
        assertFalse(audio.available());
        assertTrue(audio.reason().contains("Connection refused"));
        assertEquals(0L, audio.objects());
    }

    /** orphans：桶内 {tasks/1/a, tasks/2/b}，DB 引用 tasks/1/a → 孤儿只有 tasks/2/b（含字节）。 */
    @Test
    void orphansExcludesReferencedKeys() {
        stubPages(List.of(page(false, "tasks/1/a.wav", "100", "tasks/2/orphan.wav", "300")));
        stubReferenced("s3://" + BUCKET + "/tasks/1/a.wav");
        StorageService.Orphans orphans = newService().orphans();
        assertEquals(1L, orphans.objects());
        assertEquals(300L, orphans.bytes());
        assertEquals(List.of("tasks/2/orphan.wav"), orphans.keys());
    }

    /** cleanup dryRun=true：统计孤儿与样例但不删（verify s3 never deleteObject）。 */
    @Test
    void cleanupDryRunCountsButNeverDeletes() {
        stubPages(List.of(page(false, "tasks/1/a.wav", "100", "tasks/2/orphan.wav", "300",
                "tasks/3/orphan2.wav", "50")));
        stubReferenced("s3://" + BUCKET + "/tasks/1/a.wav");
        StorageService.Cleanup result = newService().cleanup(true);
        assertTrue(result.dryRun());
        assertEquals(2L, result.deletedObjects());
        assertEquals(350L, result.deletedBytes());
        assertEquals(1L, result.skipped(), "被引用对象计入 skipped");
        assertEquals(List.of("tasks/2/orphan.wav", "tasks/3/orphan2.wav"), result.sample());
        verify(s3, never()).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    /** cleanup dryRun=false：只真删孤儿，被引用对象不动。 */
    @Test
    void cleanupRealDeleteRemovesOnlyOrphans() {
        stubPages(List.of(page(false, "tasks/1/a.wav", "100", "tasks/2/orphan.wav", "300")));
        stubReferenced("s3://" + BUCKET + "/tasks/1/a.wav");
        StorageService.Cleanup result = newService().cleanup(false);
        assertFalse(result.dryRun());
        assertEquals(1L, result.deletedObjects());
        assertEquals(300L, result.deletedBytes());
        assertEquals(1L, result.skipped());
        verify(s3).deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(BUCKET).key("tasks/2/orphan.wav").build());
        verify(s3, never()).deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(BUCKET).key("tasks/1/a.wav").build());
    }
}
