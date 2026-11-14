package com.meetingnotes.web;

import com.meetingnotes.task.TaskQueryService;
import com.meetingnotes.task.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * 任务入口 API（T1.4 上传 / T1.5 状态查询）。
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
    private final JdbcTemplate jdbc;

    public TaskController(TaskService taskService,
                          TaskQueryService taskQueryService,
                          JdbcTemplate jdbc) {
        this.taskService = taskService;
        this.taskQueryService = taskQueryService;
        this.jdbc = jdbc;
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCreatedResponse> upload(@RequestPart("file") MultipartFile file)
            throws IOException {
        TaskCreatedResponse resp = taskService.createUpload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
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
                       r.summary, r.keywords, r.ai_tier
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
                    // keywords 列是 JSONB：取文本再解析成数组（T3.1），坏数据降级为 null 不炸接口
                    java.util.List<String> keywords = null;
                    String keywordsRaw = rs.getString("keywords");
                    if (keywordsRaw != null && !keywordsRaw.isBlank()) {
                        try {
                            keywords = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readValue(keywordsRaw, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });
                        } catch (Exception e) {
                            keywords = null;
                        }
                    }
                    return new TaskStatusResponse(rs.getLong("id"), rs.getString("status"),
                            duration, size,
                            rs.getObject("created_at", OffsetDateTime.class), rs.getString("transcript"),
                            rs.getString("error_code"), rs.getString("summary"), keywords,
                            rs.getString("ai_tier"));
                },
                id, DEFAULT_USER_ID);
        if (resp == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        return resp;
    }
}
