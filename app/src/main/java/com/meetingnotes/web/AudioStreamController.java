package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.AudioStreamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 音频流入口（T4.35b，播放器用）：
 * <ul>
 *   <li>POST /api/v1/tasks/{id}/audio-token —— 签发短时签名 URL（Bearer 由 ApiTokenFilter 横切，K-46）；</li>
 *   <li>GET /api/v1/tasks/{id}/audio?u=&amp;exp=&amp;sig= —— 免 Bearer 但必须验签+过期+绑定 taskId+userId
 *       （T4.37a 三段签名，收 T4.35b P3-1）；验签失败/过期/不匹配 → 统一 404 TASK_NOT_FOUND（不泄露路径存在性）；
 *       **绝不回退成无签名下载**。</li>
 * </ul>
 * 流式转发 MinIO 对象（不整文件进内存）；Content-Type 按 key 扩展名；Range: bytes=… → 206 +
 * Content-Range + Accept-Ranges: bytes（&lt;audio&gt; seek 必需）；无 Range → 200 全量；
 * 越界/非法 Range → 416 RANGE_NOT_SATISFIABLE（端点专属码，接入指南 §3.10）。
 */
@Controller
public class AudioStreamController {

    private final AudioStreamService audioStreamService;
    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final String bucket;

    public AudioStreamController(AudioStreamService audioStreamService,
                                 JdbcTemplate jdbc,
                                 S3Client s3,
                                 @Value("${app.storage.bucket}") String bucket) {
        this.audioStreamService = audioStreamService;
        this.jdbc = jdbc;
        this.s3 = s3;
        this.bucket = bucket;
    }

    @PostMapping("/api/v1/tasks/{id}/audio-token")
    @ResponseBody
    public AudioStreamService.AudioTokenResponse issueToken(@PathVariable("id") long id) {
        return audioStreamService.issue(id, UserContext.current());
    }

    /** u 请求参数解析：空/非数字/长度异常一律 null（调用方统一 404，不泄露）。 */
    private static Long parseUserId(String u) {
        if (u == null || u.isBlank() || !u.matches("\\d{1,12}")) {
            return null;
        }
        try {
            return Long.parseLong(u);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @GetMapping("/api/v1/tasks/{id}/audio")
    public void audio(@PathVariable("id") long id,
                      @RequestParam(value = "u", required = false) String u,
                      @RequestParam(value = "exp", required = false) String exp,
                      @RequestParam(value = "sig", required = false) String sig,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        // T4.37a：签名绑定 userId（三段 taskId+exp+userId）——u 缺失/形状异常 → 404（不泄露）
        Long userId = parseUserId(u);
        // 验签闸：失败统一 404（不泄露"路径存在但签名错"），且绝不回退成无签名下载
        if (userId == null || !audioStreamService.verify(id, userId, exp, sig)) {
            throw new ApiException(404, "TASK_NOT_FOUND", "音频签名无效或已过期，请重新签发");
        }
        // 验签成功后再校验任务归属（user_id 与 u 不符 → 404，不泄露存在性；T4.35b P3-1 收口）
        Map<String, Object> row = jdbc.query(
                "SELECT COALESCE(source_uri, '') AS source_uri, size_bytes FROM task"
                        + " WHERE id = ? AND user_id = ? AND status = 'DONE' AND deleted_at IS NULL",
                rs -> rs.next()
                        ? Map.of("source_uri", rs.getString(1), "size_bytes", rs.getLong(2))
                        : null,
                id, userId);
        if (row == null) {
            // 签名合法但任务已不在（被删/状态回退）：仍 404，不泄露
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        String sourceUri = (String) row.get("source_uri");
        long size = (Long) row.get("size_bytes");
        if (sourceUri.isBlank() || !sourceUri.startsWith("s3://" + bucket + "/")) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 没有可播放的音频对象");
        }
        String key = sourceUri.substring(("s3://" + bucket + "/").length());

        // Range 在打开 MinIO 前解析完：416 不留半截响应
        AudioStreamService.RangeSpec spec = audioStreamService.parseRange(request.getHeader("Range"), size);
        GetObjectRequest.Builder builder = GetObjectRequest.builder().bucket(bucket).key(key);
        if (spec != null) {
            builder.range("bytes=" + spec.start() + "-" + spec.last());
        }
        long length = spec == null ? size : spec.length();
        try (InputStream in = s3.getObject(builder.build(), ResponseTransformer.toInputStream())) {
            response.setStatus(spec == null ? HttpServletResponse.SC_OK : HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Accept-Ranges", "bytes");
            response.setContentType(AudioStreamService.contentTypeFor(sourceUri));
            response.setContentLengthLong(length);
            if (spec != null) {
                response.setHeader("Content-Range", "bytes " + spec.start() + "-" + spec.last() + "/" + size);
            }
            in.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                // 兜底：MinIO 侧拒绝（理论上闸上已拦）
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + size);
                response.setContentType("application/json");
                return;
            }
            throw new ApiException(500, "STORAGE_ERROR", "音频对象读取失败，请稍后重试");
        }
    }
}
