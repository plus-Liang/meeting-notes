package com.meetingnotes.web;

import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.SpeakerMapService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工说话人标注端点（T4.36，从 TaskController 拆出——M3 文件行数上限，语义不变）：
 * PATCH {"speaker_map":{"3":"说话人 1"}} → 落 task_result.speaker_map（句子下标 → 标签）；
 * {} = 清除（落 NULL，响应 {"speaker_map":null}）。校验与错误码见
 * {@link SpeakerMapService}（404 TASK_NOT_FOUND / 400 INVALID_PARAM）。
 * 认证由 ApiTokenFilter 横切（/api/v1/** 全 Bearer，K-46）；详情第 16 键 speaker_map 随 GET 返回。
 */
@RestController
public class SpeakerMapController {

    private final SpeakerMapService speakerMapService;

    public SpeakerMapController(SpeakerMapService speakerMapService) {
        this.speakerMapService = speakerMapService;
    }

    @PatchMapping(value = "/api/v1/tasks/{id}/speaker-map", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SpeakerMapService.SpeakerMapResponse updateSpeakerMap(
            @PathVariable("id") long id,
            @RequestBody SpeakerMapRequest body) {
        return speakerMapService.update(id, UserContext.current(),
                body == null ? null : body.speakerMap());
    }

    /** 请求体：{"speaker_map":{"3":"说话人 1"}}；缺 speaker_map 键视同清空。 */
    public record SpeakerMapRequest(java.util.Map<String, String> speakerMap) {
    }
}
