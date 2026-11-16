package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T4.27：批量导出 zip——ids 解析 400 矩阵/去重、zip 条目名净化、render 跳过语义、
 * streamZip 条目数/_errors.txt、全部不可导 → 400。
 */
@ExtendWith(MockitoExtension.class)
class TaskZipExportControllerTest {

    @Mock
    private JdbcTemplate jdbc;

    private TaskExportController.ExportRow exportableRow(long id, String sourceUri) {
        return new TaskExportController.ExportRow("DONE",
                "由张三负责整理材料，下周三截止。", "会议讨论了材料整理",
                "[\"材料整理\"]", "[]", null, null, sourceUri);
    }

    /* ===== parseIds 400 矩阵 ===== */

    @Test
    void parseIdsRejectsMissingBlankNonNumericAndOver20() {
        TaskZipExportController c = new TaskZipExportController(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        for (String bad : new String[]{null, "", "  ", "abc", "1,2,x", "1,,2,x"}) {
            assertEquals(400, assertThrows(ApiException.class, () -> c.exportZip(bad)).getStatus(),
                    "ids='" + bad + "' 必须 400");
        }
        StringBuilder tooMany = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            if (i > 1) tooMany.append(',');
            tooMany.append(i);
        }
        assertEquals(400, assertThrows(ApiException.class, () -> c.exportZip(tooMany.toString())).getStatus(),
                "21 个 id 必须 400");
    }

    @Test
    void parseIdsDedupesAndTrimsPreservingOrder() {
        assertEquals(List.of(1L, 2L), TaskZipExportController.parseIds("1,1,2"));
        assertEquals(List.of(1L, 2L), TaskZipExportController.parseIds(" 1 , 2 "));
        assertEquals(List.of(5L), TaskZipExportController.parseIds("5,5,5"));
    }

    /* ===== zip 条目名净化 ===== */

    @Test
    void zipEntryNameSanitizesStripsExtensionAndCapsLength() {
        assertEquals("7-lead-t421-c.mp3.md".replace(".mp3", ""),
                TaskZipExportController.zipEntryName(7L, "s3://meetingnotes/tasks/7/lead-t421-c.mp3"));
        assertEquals("8-task.md", TaskZipExportController.zipEntryName(8L, null));
        assertEquals("9-a_b.md", TaskZipExportController.zipEntryName(9L, "s3://x/a:b.mp3"),
                "非法字符替换成下划线");
        String longName = "很长的名字".repeat(30); // 150 字
        String entry = TaskZipExportController.zipEntryName(10L, "s3://x/" + longName + ".mp3");
        assertTrue(entry.length() <= 10 + 1 + 80 + 3, "超长文件名截断到上限内：" + entry.length());
    }

    /* ===== render 跳过语义（单条/zip 共用实现） ===== */

    @Test
    void renderMissingTaskReturnsNotFoundSkipReason() {
        when(jdbc.query(contains("FROM task t"), ArgumentMatchers.<RowMapper<TaskExportController.ExportRow>>any(),
                eq(99L), eq(1L))).thenReturn(List.of());

        TaskExportController.RenderedExport r =
                TaskExportController.render(99L, 1L, jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                        TaskExportController.parseInclude(null));

        assertNull(r.markdown());
        assertNull(r.entryName());
        assertEquals("NOT_FOUND", r.skipReason());
    }

    @Test
    void renderBlankTranscriptReturnsNoContentSkipReason() {
        when(jdbc.query(contains("FROM task t"), ArgumentMatchers.<RowMapper<TaskExportController.ExportRow>>any(),
                eq(9L), eq(1L)))
                .thenReturn(List.of(new TaskExportController.ExportRow("DONE", "   ", "s", "[]", "[]",
                        null, null, "s3://meetingnotes/tasks/9/a.mp3")));

        TaskExportController.RenderedExport r =
                TaskExportController.render(9L, 1L, jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                        TaskExportController.parseInclude(null));

        assertEquals("NO_CONTENT", r.skipReason());
    }

    @Test
    void renderSuccessReturnsMarkdownAndZipEntryName() {
        when(jdbc.query(contains("FROM task t"), ArgumentMatchers.<RowMapper<TaskExportController.ExportRow>>any(),
                eq(7L), eq(1L)))
                .thenReturn(List.of(exportableRow(7L, "s3://meetingnotes/tasks/7/lead.mp3")));

        TaskExportController.RenderedExport r =
                TaskExportController.render(7L, 1L, jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                        TaskExportController.parseInclude(null));

        assertNotNull(r.markdown());
        assertTrue(r.markdown().contains("## 摘要"), "与单条导出同渲染器（三节齐全）");
        assertEquals("7-lead.md", r.entryName());
        assertNull(r.skipReason());
    }

    /* ===== streamZip：条目数/文件名/_errors.txt ===== */

    @Test
    void streamZipWritesEntriesAndErrorsTxtForSkippedIds() throws Exception {
        when(jdbc.query(contains("FROM task t"), ArgumentMatchers.<RowMapper<TaskExportController.ExportRow>>any(),
                eq(7L), eq(1L)))
                .thenReturn(List.of(exportableRow(7L, "s3://meetingnotes/tasks/7/lead.mp3")));
        when(jdbc.query(contains("FROM task t"), ArgumentMatchers.<RowMapper<TaskExportController.ExportRow>>any(),
                eq(8L), eq(1L))).thenReturn(List.of()); // 竞态删除 → 跳过

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TaskZipExportController.streamZip(out, List.of(7L, 8L), 1L, jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(), TaskExportController.parseInclude(null));

        // 解包校验
        List<String> names = new java.util.ArrayList<>();
        String errorsContent = null;
        String md7 = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                names.add(entry.getName());
                String content = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                if ("_errors.txt".equals(entry.getName())) {
                    errorsContent = content;
                } else if (entry.getName().equals("7-lead.md")) {
                    md7 = content;
                }
            }
        }
        assertEquals(List.of("7-lead.md", "_errors.txt"), names, "成功条 + _errors.txt（顺序按请求 ids）");
        assertNotNull(md7);
        assertTrue(md7.contains("## 摘要"));
        assertEquals("8: NOT_FOUND", errorsContent, "跳过原因逐条写进 _errors.txt");
    }

    /* ===== 全部不可导 → 400 ===== */

    @Test
    void allSkippedReturns400BeforeStreaming() {
        when(jdbc.query(contains("t.id IN ("), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any(Object[].class))).thenReturn(List.of());

        TaskZipExportController c = new TaskZipExportController(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        ApiException e = assertThrows(ApiException.class, () -> c.exportZip("1,2"));

        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }
}
