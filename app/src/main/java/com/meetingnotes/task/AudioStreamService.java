package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 音频流短时签名（T4.35b）：详情页的 &lt;audio&gt; 要直接播放 URL，**URL 里不能出现 API_TOKEN**——
 * 签发（POST /tasks/{id}/audio-token，Bearer 保护）产出一条短时签名 URL；
 * 播放（GET /tasks/{id}/audio?sig=&exp=，免 Bearer）只认签名。
 *
 * <p>签名口径：HMAC-SHA256，key = {@code app.security.api-token}（复用现有键派生，不新增 secret 键），
 * 数据 = {@code taskId + "\n" + exp}（exp 为 epoch 秒）——**绑定 taskId 与 exp**，改任一项即失效。
 * TTL {@code app.audio.token-ttl-sec}（env AUDIO_TOKEN_TTL_SEC，默认 600 秒；URL 可分享但只读、短时）。
 *
 * <p>签发拒绝语义（卡面）：任务不存在/不属于当前用户 → 404 TASK_NOT_FOUND；
 * PASTE（source_uri 是 s3 文本对象）/ 无 source_uri / 非 DONE → 400 INVALID_PARAM（明确 4xx 不是 500）。
 * 验签失败/过期/与 taskId 不匹配 → 统一 404 TASK_NOT_FOUND（不泄露路径存在性，卡面推荐口径）。
 */
@Service
public class AudioStreamService {

    /** Range 解析结果：start/last 为闭区间字节位；singleton=请求无 Range 头（走 200 全量）。 */
    public record RangeSpec(long start, long last) {
        public long length() {
            return last - start + 1;
        }
    }

    /** 签发响应（对外 snake_case，全局 Jackson 策略）：url 相对路径（前端 &lt;audio src&gt; 直接用）。 */
    public record AudioTokenResponse(String url, OffsetDateTime expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final byte[] hmacKey;
    private final long ttlSec;

    public AudioStreamService(JdbcTemplate jdbc,
                              @Value("${app.security.api-token}") String apiToken,
                              @Value("${app.audio.token-ttl-sec}") long ttlSec) {
        this.jdbc = jdbc;
        this.hmacKey = (apiToken == null ? "" : apiToken.trim()).getBytes(StandardCharsets.UTF_8);
        this.ttlSec = ttlSec;
    }

    /** 签发短时播放 URL：存在性/归属在 404，无音频在 400（都是签发前的显式闸，绝不 500）。 */
    public AudioTokenResponse issue(long taskId, long userId) {
        Map<String, Object> row = jdbc.query(
                "SELECT status, source_type, COALESCE(source_uri, '') AS source_uri FROM task WHERE id = ? AND user_id = ?",
                rs -> rs.next()
                        ? Map.of("status", rs.getString(1), "source_type", rs.getString(2), "source_uri", rs.getString(3))
                        : null,
                taskId, userId);
        if (row == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
        String status = (String) row.get("status");
        String sourceType = (String) row.get("source_type");
        String sourceUri = (String) row.get("source_uri");
        if (!"DONE".equals(status) || "PASTE".equals(sourceType) || sourceUri == null || sourceUri.isBlank()) {
            // PASTE 的 source_uri 是 s3 文本对象（不是音频），必须显式拒绝（卡面逐字）
            throw new ApiException(400, "INVALID_PARAM",
                    "该任务没有可播放的音频（文本任务或尚未完成转写）");
        }
        long exp = Instant.now().getEpochSecond() + ttlSec;
        String sig = sign(taskId, exp);
        String url = "/api/v1/tasks/" + taskId + "/audio?exp=" + exp + "&sig=" + sig;
        return new AudioTokenResponse(url, OffsetDateTime.ofInstant(Instant.ofEpochSecond(exp),
                OffsetDateTime.now().getOffset()));
    }

    /** HMAC-SHA256(key=API_TOKEN, data=taskId+"\n"+exp) 的 hex（绑定 taskId 与 exp，改任一即失效）。 */
    String sign(long taskId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] out = mac.doFinal((taskId + "\n" + exp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 验签：空/形状异常/过期/不匹配一律 false（调用方统一 404，不区分失败原因，不泄露）。 */
    public boolean verify(long taskId, String exp, String sig) {
        if (exp == null || sig == null || exp.isBlank() || sig.isBlank()) {
            return false;
        }
        if (!exp.matches("\\d{1,12}") || sig.length() != 64 || !sig.matches("[0-9a-f]{64}")) {
            return false; // 长度异常/形状异常直接拒（卡判据：空 sig/长度异常 → 拒绝）
        }
        long expSec;
        try {
            expSec = Long.parseLong(exp);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (expSec < now || expSec > now + ttlSec * 10) {
            return false; // 已过期；上限防远古/离谱未来值
        }
        String expected = sign(taskId, expSec);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8)); // 常量时间比较
    }

    /**
     * Range 头解析（&lt;audio&gt; seek 必需）：bytes=a-b / bytes=a- / bytes=-n 三形态；
     * 合法 → {@link RangeSpec}（b 超出文件尾按 HTTP 语义收在 size-1）；无 Range → null（200 全量）；
     * 非法/越界（a ≥ size、a&gt;b、n=0、多段、非 bytes= 前缀）→ 416 RANGE_NOT_SATISFIABLE（卡判据）。
     */
    public RangeSpec parseRange(String rangeHeader, long size) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        ApiException unsatisfiable = new ApiException(416, "RANGE_NOT_SATISFIABLE",
                "Range 头非法或超出文件范围（文件 " + size + " 字节）");
        if (size <= 0 || !rangeHeader.startsWith("bytes=") || rangeHeader.contains(",")) {
            throw unsatisfiable; // 多段 Range 不支持（<audio> 单段 seek 足够），按 416 明确拒绝
        }
        String spec = rangeHeader.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw unsatisfiable;
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        try {
            if (first.isEmpty()) {
                // suffix 形态 bytes=-n：最后 n 字节；n=0 → 416
                long n = Long.parseLong(last);
                if (n <= 0) {
                    throw unsatisfiable;
                }
                long start = Math.max(0, size - n);
                return new RangeSpec(start, size - 1);
            }
            long start = Long.parseLong(first);
            if (start < 0 || start >= size) {
                throw unsatisfiable; // 起点越界（含空文件）
            }
            long end = last.isEmpty() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
            if (end < start) {
                throw unsatisfiable; // 乱序（bytes=10-9）
            }
            return new RangeSpec(start, end);
        } catch (NumberFormatException e) {
            throw unsatisfiable;
        }
    }

    /** Content-Type 按对象 key 的扩展名（<audio> 能解码的前缀；未知回退 application/octet-stream）。 */
    public static String contentTypeFor(String sourceUri) {
        String key = sourceUri == null ? "" : sourceUri.toLowerCase(java.util.Locale.ROOT);
        if (key.endsWith(".wav")) {
            return "audio/wav";
        }
        if (key.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (key.endsWith(".m4a") || key.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (key.endsWith(".opus") || key.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (key.endsWith(".webm")) {
            return "audio/webm";
        }
        if (key.endsWith(".flac")) {
            return "audio/flac";
        }
        return "application/octet-stream";
    }
}
