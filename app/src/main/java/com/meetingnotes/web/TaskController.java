package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.task.BatchTaskService;
import com.meetingnotes.task.GlossaryRepository;
import com.meetingnotes.task.TaskMutationService;
import com.meetingnotes.task.TaskQueryService;
import com.meetingnotes.task.TaskRetryService;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任务入口 API（T1.4 上传 / T1.5 状态查询 / T3.5 改词回流与待办勾选删除 / T3.7 PASTE / T3.9 模板参数）。
 * 导出端点在 {@link TaskExportController}（T3.9 抽出，行为不变）。
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
    /** 状态筛选白名单（包内共享：T4.29 起列表端点在 TaskListController）。 */
    static final java.util.Set<String> ALLOWED_STATUS_FILTER =
            java.util.Set.of("PENDING", "RUNNING", "RETRYING", "DONE", "DEAD");

    private final TaskService taskService;
    private final BatchTaskService batchTaskService;
    private final TaskQueryService taskQueryService;
    private final TaskMutationService taskMutationService;
    private final TextTaskService textTaskService;
    private final TaskRetryService taskRetryService;
    private final GlossaryRepository glossaryRepository;
    private final JdbcTemplate jdbc;
    /** F-10：详情解析用的单例 ObjectMapper（此前每行 new，属技术债）。 */
    private final ObjectMapper mapper;

    public TaskController(TaskService taskService,
                          BatchTaskService batchTaskService,
                          TaskQueryService taskQueryService,
                          TaskMutationService taskMutationService,
                          TextTaskService textTaskService,
                          TaskRetryService taskRetryService,
                          GlossaryRepository glossaryRepository,
                          JdbcTemplate jdbc,
                          ObjectMapper mapper) {
        this.taskService = taskService;
        this.batchTaskService = batchTaskService;
        this.taskQueryService = taskQueryService;
        this.taskMutationService = taskMutationService;
        this.textTaskService = textTaskService;
        this.taskRetryService = taskRetryService;
        this.glossaryRepository = glossaryRepository;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCreatedResponse> upload(@RequestPart("file") MultipartFile file,
                                                      @RequestPart(value = "template", required = false) String template)
            throws IOException {
        // T3.7：字幕（.srt/.ass，按文件名后缀大小写不敏感）先于魔数闸分派——纯文本过不了
        // MediaMagic.detect，必须在这里转成文本任务；其余后缀仍走原音频路径（逐字节不动）。
        // T3.9：template 为可选表单字段（显式/文件名自动推荐/默认的解析在各 service 内做）。
        String name = file.getOriginalFilename() == null ? ""
                : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".srt") || name.endsWith(".ass")) {
            return ResponseEntity.status(HttpStatus.CREATED).body(textTaskService.createSubtitle(file, template));
        }
        TaskCreatedResponse resp = taskService.createUpload(file, template);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 批量提交（T4.7 / US-8）：multipart 字段 files 可重复 + 可选 template。
     * 请求合法即 200（即使部分失败）——逐项结果与汇总见 {@link BatchTaskService}；
     * 0 文件 / 超 {@code BATCH_MAX_FILES} → 400 INVALID_PARAM；总体积超限由 servlet 引擎层 413。
     * 与单文件端点互不影响（K-46：原 /tasks 行为一字不改）。
     */
    @PostMapping(value = "/tasks/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchTaskService.BatchResult> uploadBatch(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart(value = "template", required = false) String template) {
        return ResponseEntity.ok(batchTaskService.createBatch(files, template));
    }

    /** PASTE 入口（T3.7 / US-2）：贴文本直接建任务，跳过 ASR（worker 按 source_type=PASTE 走无音频分支）。 */
    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskCreatedResponse> paste(@RequestBody PasteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(textTaskService.createPaste(body == null ? null : body.text(),
                        body == null ? null : body.template()));
    }

    /** 批量重试（T4.1）：只处理 DEAD（复活口径与重传复活 D-049 逐字一致，不消费配额）；非 DEAD/不存在/非本人 → 逐条 skipped（NOT_DEAD / NOT_FOUND），不整体失败。 */
    @PostMapping(value = "/tasks/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TaskRetryResponse retry(@RequestBody RetryRequest body) {
        TaskRetryService.RetryOutcome outcome = taskRetryService.retry(body == null ? null : body.ids());
        List<TaskRetryResponse.SkippedView> skipped = outcome.skipped().stream()
                .map(s -> new TaskRetryResponse.SkippedView(s.id(), s.reason())).toList();
        return new TaskRetryResponse(outcome.retried(), skipped);
    }

    /** 术语表只读列表（T4.1 设置页）：hits desc 取 ≤100。 */
    @GetMapping("/glossary")
    public List<GlossaryRepository.GlossaryRow> glossary() {
        return glossaryRepository.listTop(DEFAULT_USER_ID, 100);
    }

    /** 首页 → 提交页（T1.6）。静态资源由 Spring Boot 提供，这里只做根路径转发。 */
    @GetMapping("/")
    public String home() {
        return "forward:/index.html";
    }


    /** 查任务状态（前端轮询/接入方）。未到 DONE 时 transcript 恒 null；error_code 仅 RETRYING/DEAD 有值（S-3）；查询带 user_id（D-040），不存在时 404 TASK_NOT_FOUND。 */
    @GetMapping("/tasks/{id}")
    public TaskStatusResponse status(@PathVariable("id") long id) {
        TaskStatusResponse resp = jdbc.query("""
                SELECT t.id, t.status, t.duration_sec, t.size_bytes, t.created_at, t.error_code,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript,
                       r.summary, r.keywords, r.ai_tier, r.todos, r.keypoints,
                       CASE WHEN t.status = 'DONE' THEN r.transcript_punctuated END AS transcript_punctuated,
                       r.template,
                       CASE WHEN t.status = 'DONE' THEN r.segments::text END AS segments,
                       r.speaker_map::text AS speaker_map
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
                    // keypoints 列是 JSONB（T4.17/D-109）：缺失/坏数据 → null（历史任务与"未生成"同语义），不炸接口
                    List<KeypointView> keypoints = null;
                    String keypointsRaw = rs.getString("keypoints");
                    if (keypointsRaw != null && !keypointsRaw.isBlank()) {
                        try {
                            JsonNode kpArray = mapper.readTree(keypointsRaw);
                            if (kpArray.isArray()) {
                                keypoints = KeypointView.listFrom(kpArray);
                            }
                        } catch (Exception e) {
                            keypoints = null;
                        }
                    }
                    // segments 列是 JSONB（T4.35a 第 15 键）：句子级时间戳数组；旧任务/PASTE/坏数据 → null（不是 []），不炸接口
                    List<TaskStatusResponse.SegmentView> segments = null;
                    String segmentsRaw = rs.getString("segments");
                    if (segmentsRaw != null && !segmentsRaw.isBlank()) {
                        try {
                            JsonNode segArray = mapper.readTree(segmentsRaw);
                            if (segArray.isArray()) {
                                segments = new ArrayList<>();
                                for (JsonNode seg : segArray) {
                                    segments.add(new TaskStatusResponse.SegmentView(
                                            seg.path("start").asDouble(),
                                            seg.path("end").asDouble(),
                                            seg.path("text").asText("")));
                                }
                            }
                        } catch (Exception e) {
                            segments = null;
                        }
                    }
                    // speaker_map 列是 JSONB（T4.36 第 16 键）：未标注/清除/旧任务/坏数据 → null（不是 {}），不炸接口
                    Map<String, String> speakerMap = null;
                    String speakerRaw = rs.getString("speaker_map");
                    if (speakerRaw != null && !speakerRaw.isBlank()) {
                        try {
                            JsonNode smObj = mapper.readTree(speakerRaw);
                            if (smObj.isObject()) {
                                Map<String, String> parsed = new java.util.LinkedHashMap<>();
                                smObj.fields().forEachRemaining(f -> parsed.put(f.getKey(), f.getValue().asText()));
                                speakerMap = parsed;
                            }
                        } catch (Exception e) { speakerMap = null; }
                    }
                    return new TaskStatusResponse(rs.getLong("id"), rs.getString("status"),
                            duration, size,
                            rs.getObject("created_at", OffsetDateTime.class), rs.getString("transcript"),
                            rs.getString("error_code"), rs.getString("summary"), keywords,
                            rs.getString("ai_tier"), todos, keypoints,
                            rs.getString("transcript_punctuated"), rs.getString("template"), segments,
                            speakerMap);
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
