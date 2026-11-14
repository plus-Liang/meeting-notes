package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.task.GlossaryRepository;
import com.meetingnotes.task.TaskMutationService;
import com.meetingnotes.task.TaskQueryService;
import com.meetingnotes.task.TaskService;
import com.meetingnotes.task.TextTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务入口 API（T1.4 上传 / T1.5 状态查询 / T3.5 改词回流与待办勾选删除）。
 *
 * <p>认证（API_TOKEN / 401，spec US-9）在后续卡接入，先固定单用户；
 * 所有查询必须带 user_id（D-040）。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    /** 认证接入前固定单用户（与 TaskService 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    /** T2.12：status 过滤只允许任务状态机的五个值，其它 → 400 INVALID_PARAM（不再静默空列表）。 */
    private static final java.util.Set<String> ALLOWED_STATUS_FILTER =
            java.util.Set.of("PENDING", "RUNNING", "RETRYING", "DONE", "DEAD");

    private final TaskService taskService;
    private final TaskQueryService taskQueryService;
    private final TaskMutationService taskMutationService;
    private final TextTaskService textTaskService;
    private final JdbcTemplate jdbc;
    /** F-10：详情解析用的单例 ObjectMapper（此前每行 new，属技术债）。 */
    private final ObjectMapper mapper;

    public TaskController(TaskService taskService,
                          TaskQueryService taskQueryService,
                          TaskMutationService taskMutationService,
                          TextTaskService textTaskService,
                          JdbcTemplate jdbc,
                          ObjectMapper mapper) {
        this.taskService = taskService;
        this.taskQueryService = taskQueryService;
        this.taskMutationService = taskMutationService;
        this.textTaskService = textTaskService;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCreatedResponse> upload(@RequestPart("file") MultipartFile file)
            throws IOException {
        // T3.7：字幕（.srt/.ass，按文件名后缀大小写不敏感）先于魔数闸分派——纯文本过不了
        // MediaMagic.detect，必须在这里转成文本任务；其余后缀仍走原音频路径（逐字节不动）。
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".srt") || name.endsWith(".ass")) {
            return ResponseEntity.status(HttpStatus.CREATED).body(textTaskService.createSubtitle(file));
        }
        TaskCreatedResponse resp = taskService.createUpload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /** PASTE 入口（T3.7 / US-2）：贴文本直接建任务，跳过 ASR（worker 按 source_type=PASTE 走无音频分支）。 */
    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskCreatedResponse> paste(@RequestBody PasteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(textTaskService.createPaste(body == null ? null : body.text()));
    }

    /**
     * 导出 Markdown（T3.7 / US-2）：三节固定顺序（摘要/关键词/待办），待办带 done 勾选状态。
     * format 目前只支持 md；任务不存在 → 404；无内容（未 DONE/transcript 空）→ 400。
     */
    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<String> export(@PathVariable("id") long id,
                                         @RequestParam(name = "format", required = false) String format) {
        if (!"md".equals(format)) {
            throw new ApiException(400, "INVALID_PARAM", "format 目前只支持 md");
        }
        record ExportRow(String status, String transcript, String summary, String keywords, String todos) {
        }
        List<ExportRow> rows = jdbc.query("""
                SELECT t.status,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript,
                       r.summary, r.keywords::text AS keywords, r.todos::text AS todos
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE t.id = ? AND t.user_id = ?
                """, (rs, i) -> new ExportRow(rs.getString("status"), rs.getString("transcript"),
                rs.getString("summary"), rs.getString("keywords"), rs.getString("todos")),
                id, DEFAULT_USER_ID);
        if (rows.isEmpty()) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        ExportRow row = rows.get(0);
        if (row.transcript() == null || row.transcript().isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "任务没有可导出的内容（未完成或无转写文本）");
        }
        List<String> keywords = null;
        List<TodoItemView> todos = null;
        try {
            if (row.keywords() != null && !row.keywords().isBlank()) {
                keywords = mapper.readValue(row.keywords(), new TypeReference<List<String>>() { });
            }
            JsonNode todosNode = row.todos() == null || row.todos().isBlank()
                    ? null : mapper.readTree(row.todos());
            if (todosNode != null && todosNode.isArray()) {
                todos = TodoItemView.listFrom(todosNode);
            }
        } catch (Exception e) {
            keywords = null;
            todos = null;
        }
        String markdown = TaskMarkdownExporter.export(row.summary(), keywords, todos);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"task-" + id + ".md\"")
                .body(markdown);
    }

    /** 首页 → 提交页（T1.6）。静态资源由 Spring Boot 提供，这里只做根路径转发。 */
    @GetMapping("/")
    public String home() {
        return "forward:/index.html";
    }

    /**
     * 服务端任务列表（T2.10 / D-053）：keyset 分页（before_id 游标，禁 OFFSET），
     * user_id 服务端过滤（D-040）。limit 默认 20、上限 50；非数字/越界 → 400（码沿用既有校验类）。
     */
    @GetMapping("/tasks")
    public TaskQueryService.TaskListResponse list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String limit,
            @org.springframework.web.bind.annotation.RequestParam(name = "before_id", required = false) String beforeId) {
        int lim = TaskQueryService.DEFAULT_LIMIT;
        if (limit != null) {
            try {
                lim = Integer.parseInt(limit.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "INVALID_PARAM", "limit 必须是数字");
            }
            if (lim < 1 || lim > TaskQueryService.MAX_LIMIT) {
                throw new ApiException(400, "INVALID_PARAM",
                        "limit 取值范围 1.." + TaskQueryService.MAX_LIMIT);
            }
        }
        Long before = null;
        if (beforeId != null && !beforeId.isBlank()) {
            try {
                before = Long.parseLong(beforeId.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "INVALID_PARAM", "before_id 必须是数字");
            }
        }
        String statusFilter = null;
        if (status != null && !status.isBlank()) {
            statusFilter = status.trim();
            if (!ALLOWED_STATUS_FILTER.contains(statusFilter)) {
                throw new ApiException(400, "INVALID_PARAM",
                        "status 只允许 PENDING/RUNNING/RETRYING/DONE/DEAD");
            }
        }
        return taskQueryService.list(DEFAULT_USER_ID, statusFilter, lim, before);
    }

    /**
     * 查任务状态（前端轮询 / 接入方）。未到 DONE 时 transcript 恒为 null；
     * error_code 仅 RETRYING/DEAD 有值（S-3）；查询带 user_id（D-040），不存在时 404 TASK_NOT_FOUND。
     */
    @GetMapping("/tasks/{id}")
    public TaskStatusResponse status(@PathVariable("id") long id) {
        TaskStatusResponse resp = jdbc.query("""
                SELECT t.id, t.status, t.duration_sec, t.size_bytes, t.created_at, t.error_code,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript,
                       r.summary, r.keywords, r.ai_tier, r.todos
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE t.id = ? AND t.user_id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    // INT4 列 JDBC 返回 Integer，必须 getLong + wasNull，不能强转 getObject
                    long durationSec = rs.getLong("duration_sec");
                    Long duration = rs.wasNull() ? null : durationSec;
                    long sizeBytes = rs.getLong("size_bytes");
                    Long size = rs.wasNull() ? null : sizeBytes;
                    // keywords 列是 JSONB：取文本再解析成数组（T3.1），坏数据降级为 null 不炸接口（F-10：单例 mapper）
                    List<String> keywords = null;
                    String keywordsRaw = rs.getString("keywords");
                    if (keywordsRaw != null && !keywordsRaw.isBlank()) {
                        try {
                            keywords = mapper.readValue(keywordsRaw, new TypeReference<List<String>>() { });
                        } catch (Exception e) {
                            keywords = null;
                        }
                    }
                    // todos 列是 JSONB（T3.2/T3.5）：JsonNode → 视图（元素只增 done，老数据无 done 按 false 读）；
                    // 坏数据降级为 null 不炸接口
                    List<TodoItemView> todos = null;
                    String todosRaw = rs.getString("todos");
                    if (todosRaw != null && !todosRaw.isBlank()) {
                        try {
                            JsonNode array = mapper.readTree(todosRaw);
                            if (array.isArray()) {
                                todos = TodoItemView.listFrom(array);
                            }
                        } catch (Exception e) {
                            todos = null;
                        }
                    }
                    return new TaskStatusResponse(rs.getLong("id"), rs.getString("status"),
                            duration, size,
                            rs.getObject("created_at", OffsetDateTime.class), rs.getString("transcript"),
                            rs.getString("error_code"), rs.getString("summary"), keywords,
                            rs.getString("ai_tier"), todos);
                },
                id, DEFAULT_USER_ID);
        if (resp == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        return resp;
    }

    /**
     * 改词回流（T3.5）：事务内替换本任务 transcript/summary/keywords/todos 里的错词，
     * 并沉淀 glossary（hits 递增，供 T3.6 注入）。请求 Content-Type 只接受 application/json。
     */
    @PostMapping(value = "/tasks/{id}/corrections", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CorrectionResponse applyCorrections(@PathVariable("id") long id,
                                               @RequestBody CorrectionsRequest body) {
        List<TaskMutationService.Correction> corrections =
                body == null || body.corrections() == null ? List.of() : body.corrections();
        TaskMutationService.CorrectionOutcome outcome =
                taskMutationService.applyCorrections(id, DEFAULT_USER_ID, corrections);
        List<CorrectionResponse.GlossaryEntryView> entries = new ArrayList<>();
        for (GlossaryRepository.Entry entry : outcome.glossary()) {
            entries.add(new CorrectionResponse.GlossaryEntryView(
                    entry.wrongText(), entry.rightText(), entry.hits()));
        }
        return new CorrectionResponse(outcome.taskId(), outcome.replacedCount(), entries);
    }

    /** 待办勾选/取消（T3.5，幂等）：写库持久，重新 GET 详情可见。 */
    @PatchMapping("/tasks/{id}/todos/{index}")
    public TodoUpdateResponse setTodoDone(@PathVariable("id") long id,
                                          @PathVariable("index") int index,
                                          @RequestBody TodoPatchRequest body) {
        if (body == null || body.done() == null) {
            throw new ApiException(400, "INVALID_PARAM", "请求体必须包含布尔字段 done");
        }
        TaskMutationService.TodoUpdateResult result =
                taskMutationService.setTodoDone(id, DEFAULT_USER_ID, index, body.done());
        TaskMutationService.TodoData data = result.todoData();
        return new TodoUpdateResponse(result.taskId(), result.index(),
                new TodoItemView(data.action(), data.owner(), data.deadline(), data.done()));
    }

    /** 待办删除（T3.5，**非幂等**：删除后后续 index 移位）。 */
    @DeleteMapping("/tasks/{id}/todos/{index}")
    public TodoDeleteResponse deleteTodo(@PathVariable("id") long id,
                                         @PathVariable("index") int index) {
        TaskMutationService.TodoDeleteResult result =
                taskMutationService.deleteTodo(id, DEFAULT_USER_ID, index);
        return new TodoDeleteResponse(result.taskId(), result.index(),
                result.removedCount(), result.remainingCount());
    }
}
