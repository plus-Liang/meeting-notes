package com.meetingnotes.web;

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

    private final TaskService taskService;
    private final JdbcTemplate jdbc;

    public TaskController(TaskService taskService, JdbcTemplate jdbc) {
        this.taskService = taskService;
        this.jdbc = jdbc;
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCreatedResponse> upload(@RequestPart("file") MultipartFile file)
            throws IOException {
        TaskCreatedResponse resp = taskService.createUpload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 查任务状态（前端轮询 / 接入方）。未到 DONE 时 transcript 恒为 null；
     * 查询带 user_id（D-040），不存在时 404 TASK_NOT_FOUND。
     */
    @GetMapping("/tasks/{id}")
    public TaskStatusResponse status(@PathVariable("id") long id) {
        TaskStatusResponse resp = jdbc.query("""
                SELECT t.id, t.status, t.duration_sec, t.size_bytes, t.created_at,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript
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
                    return new TaskStatusResponse(rs.getLong("id"), rs.getString("status"),
                            duration, size,
                            rs.getObject("created_at", OffsetDateTime.class), rs.getString("transcript"));
                },
                id, DEFAULT_USER_ID);
        if (resp == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        return resp;
    }
}
