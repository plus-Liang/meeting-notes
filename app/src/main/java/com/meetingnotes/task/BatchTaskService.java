package com.meetingnotes.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 批量提交编排（T4.7 / US-8 / 需求确认单 D2：一次最多 {@code BATCH_MAX_FILES} 个文件）。
 *
 * <p>逐项独立：每个文件走与单文件**完全相同**的路径（音频 → {@link TaskService#createUpload}、
 * 字幕 → {@link TextTaskService#createSubtitle}，与单文件端点同一套校验/幂等/配额/入队逻辑，
 * 本类只做编排，不复制任何一条业务规则）。单项失败（含配额 429）只影响该项，其余继续。
 * 请求合法即 200，即使部分失败（逐项结果 + 汇总）。
 *
 * <p>总体积上限在 servlet 引擎层拦截：{@code spring.servlet.multipart.max-request-size}
 * = {@code TASK_BATCH_MAX_SIZE_BYTES}（默认 2GB），超限由
 * {@code MaxUploadSizeExceededException} 映射为 413 SIZE_EXCEEDED；
 * 单文件仍受 {@code max-file-size}（TASK_MAX_SIZE_BYTES，200MB）硬闸。
 *
 * <p>汇总口径：{@code accepted} 含 {@code reused}（与单文件语义一致）；
 * {@code total_duration_sec} = 各成功项 {@code duration_sec} 非空者之和（reused 项返回原任务
 * 同样的探测时长；PASTE/字幕无时长则跳过）；{@code estimated_sec} = {@link #estimateSec}
 * ——**只是预估，不承诺**（系数 {@code BATCH_ESTIMATE_RTF} 默认 0.15，含 ASR+结构化的保守估计）。
 */
@Service
public class BatchTaskService {

    private static final Logger log = LoggerFactory.getLogger(BatchTaskService.class);

    private final TaskService taskService;
    private final TextTaskService textTaskService;
    private final int maxFiles;
    private final double estimateRtf;

    public BatchTaskService(TaskService taskService,
                            TextTaskService textTaskService,
                            @Value("${app.limits.batch-max-files}") int maxFiles,
                            @Value("${app.limits.batch-estimate-rtf}") double estimateRtf) {
        this.taskService = taskService;
        this.textTaskService = textTaskService;
        this.maxFiles = maxFiles;
        this.estimateRtf = estimateRtf;
    }

    /**
     * 逐项结果（对外 JSON snake_case 由全局 Jackson 策略转换，D-041；null 键不输出）：
     * 成功项 {@code file_name/task_id/status/reused}，失败项 {@code file_name/code/message}
     * （code 复用统一错误码，D-057，如 INVALID_MEDIA / QUOTA_EXCEEDED / SIZE_EXCEEDED）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchItem(String fileName, Long taskId, String status, Boolean reused,
                            String code, String message) {
    }

    /** 汇总：accepted 含 reused；total_duration_sec / estimated_sec 见类注释口径。 */
    public record BatchSummary(int accepted, int reused, int failed,
                               long totalDurationSec, long estimatedSec) {
    }

    public record BatchResult(List<BatchItem> items, BatchSummary summary) {
    }

    /**
     * 预估耗时（纯函数，便于单测）：{@code round(total_duration_sec * RTF + accepted * 2)}。
     * 常数 2 秒/项覆盖逐项入库/存储/入队的固定开销；系数只是预估不承诺（D2）。
     */
    static long estimateSec(long totalDurationSec, int accepted, double rtf) {
        return Math.round(totalDurationSec * rtf + accepted * 2.0);
    }

    public BatchResult createBatch(List<MultipartFile> files, String template) {
        if (files == null || files.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM", "multipart 字段 files 缺失或为空");
        }
        if (files.size() > maxFiles) {
            throw new ApiException(400, "INVALID_PARAM",
                    "单次批量最多 " + maxFiles + " 个文件（需求确认单 D2），实际 " + files.size() + " 个");
        }

        List<BatchItem> items = new ArrayList<>(files.size());
        long totalDurationSec = 0;
        int reused = 0;
        int failed = 0;
        for (MultipartFile file : files) {
            String name = displayName(file);
            try {
                TaskCreatedResponse r = submitOne(file, template);
                items.add(new BatchItem(name, r.taskId(), r.status(), r.reused(), null, null));
                if (r.reused()) {
                    reused++;
                }
                if (r.durationSec() != null) {
                    totalDurationSec += r.durationSec();
                }
            } catch (ApiException e) {
                // 逐项失败隔离：配额 429 / 校验 400 / 存储 500 都只影响该项，其余继续
                log.warn("批量项 {} 受理失败：{} {}", name, e.getCode(), e.getMessage());
                items.add(new BatchItem(name, null, null, null, e.getCode(), e.getMessage()));
                failed++;
            } catch (java.io.IOException e) {
                // 分区读取失败同样是单项问题，不拖垮整批
                log.warn("批量项 {} 读取失败", name, e);
                items.add(new BatchItem(name, null, null, null, "INVALID_PARAM", "文件读取失败"));
                failed++;
            }
        }
        int accepted = files.size() - failed;
        BatchSummary summary = new BatchSummary(accepted, reused, failed,
                totalDurationSec, estimateSec(totalDurationSec, accepted, estimateRtf));
        log.info("批量提交：{} 项（accepted={} reused={} failed={}），总时长 {}s，预估 {}s",
                files.size(), accepted, reused, failed, totalDurationSec, summary.estimatedSec());
        return new BatchResult(items, summary);
    }

    /** 与单文件端点同一分派：字幕后缀先于魔数闸（纯文本过不了 MediaMagic），其余走音频路径。 */
    private TaskCreatedResponse submitOne(MultipartFile file, String template) throws java.io.IOException {
        String name = file.getOriginalFilename() == null ? ""
                : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".srt") || name.endsWith(".ass")) {
            return textTaskService.createSubtitle(file, template);
        }
        return taskService.createUpload(file, template);
    }

    private static String displayName(MultipartFile file) {
        String base = file.getOriginalFilename();
        return base == null || base.isBlank() ? "(unnamed)" : base;
    }
}
