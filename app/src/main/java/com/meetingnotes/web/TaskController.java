package com.meetingnotes.web;

import com.meetingnotes.task.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 任务入口 API（T1.4）。
 *
 * <p>认证（API_TOKEN / 401，spec US-9）在后续卡接入，先固定单用户。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCreatedResponse> upload(@RequestPart("file") MultipartFile file)
            throws IOException {
        TaskCreatedResponse resp = taskService.createUpload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }
}
