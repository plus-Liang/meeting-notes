package com.meetingnotes.upload;

/**
 * 媒体文件魔数（magic number）识别 —— spec N6：只信文件头，不信扩展名与 MIME。
 *
 * <p>白名单 = spec §4「格式支持」显式承诺的 14 种音/视频容器：
 * 音频 mp3 / wav / m4a / aac / flac / ogg / opus / amr，
 * 视频 mp4 / mov / mkv / avi / flv / webm。
 * 不在白名单（含可执行文件、文本、未知格式）一律返回 null，由调用方拒绝。
 */
public final class MediaMagic {

    private MediaMagic() {
    }

    /**
     * @param head 文件前若干字节（建议 ≥16 字节，不足则按已有字节判断）
     * @return 识别出的格式标签（如 "wav" / "mp3" / "mp4"）；无法识别返回 null
     */
    public static String detect(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        // RIFF 容器：wav（RIFF....WAVE）/ avi（RIFF....AVI ）
        if (ascii(b, 0, "RIFF")) {
            if (b.length >= 12 && ascii(b, 8, "WAVE")) {
                return "wav";
            }
            if (b.length >= 12 && ascii(b, 8, "AVI ")) {
                return "avi";
            }
            return null; // 是 RIFF 但不是 wav/avi，不承诺支持
        }
        // ID3v2 或 MPEG 帧同步 → mp3 / aac(ADTS)
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return "mp3";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) {
            // MPEG 音频头：AAAA AAAA AAABBCCD，C(2bit)=layer；layer=00 即 ADTS(AAC)，否则 MP3
            int layer = (b[1] >> 1) & 0x03;
            return layer == 0 ? "aac" : "mp3";
        }
        // ISO BMFF（ftyp box 在偏移 4）：mp4 / m4a / mov
        if (b.length >= 8 && ascii(b, 4, "ftyp")) {
            return "mp4";
        }
        if (ascii(b, 0, "fLaC")) {
            return "flac";
        }
        if (ascii(b, 0, "OggS")) {
            return "ogg"; // ogg 与 opus 同为 OggS 容器
        }
        // EBML 头：mkv / webm
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "webm";
        }
        if (b.length >= 3 && b[0] == 'F' && b[1] == 'L' && b[2] == 'V') {
            return "flv";
        }
        if (b.length >= 5 && ascii(b, 0, "#!AMR")) {
            return "amr";
        }
        return null;
    }

    private static boolean ascii(byte[] b, int off, String s) {
        if (b.length < off + s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
