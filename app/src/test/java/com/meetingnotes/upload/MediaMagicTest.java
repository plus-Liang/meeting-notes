package com.meetingnotes.upload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 魔数识别单测（spec N6：可执行文件改名 .mp3 必须被拒）。
 * 全部用真实格式头字节构造，不依赖外部文件。
 */
class MediaMagicTest {

    private static byte[] bytes(int... ints) {
        byte[] b = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) {
            b[i] = (byte) ints[i];
        }
        return b;
    }

    private static byte[] withAscii(byte[] prefix, String tail) {
        byte[] t = tail.getBytes();
        byte[] out = new byte[prefix.length + t.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(t, 0, out, prefix.length, t.length);
        return out;
    }

    @Test
    void wav_riff_wave() {
        assertEquals("wav", MediaMagic.detect(withAscii(new byte[0], "RIFF1234WAVEfmt ")));
    }

    @Test
    void avi_riff_avi() {
        assertEquals("avi", MediaMagic.detect(withAscii(new byte[0], "RIFF1234AVI LIST")));
    }

    @Test
    void riff_but_neither_wave_nor_avi_is_rejected() {
        assertNull(MediaMagic.detect(withAscii(new byte[0], "RIFF1234JUNKxxxx")));
    }

    @Test
    void mp3_id3() {
        assertEquals("mp3", MediaMagic.detect(bytes('I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)));
    }

    @Test
    void mp3_mpeg_frame_sync() {
        // 0xFF 0xFB：MPEG-1 Layer III
        assertEquals("mp3", MediaMagic.detect(bytes(0xFF, 0xFB, 0x90, 0x00)));
    }

    @Test
    void aac_adts() {
        // 0xFF 0xF1：ADTS AAC（layer=00）
        assertEquals("aac", MediaMagic.detect(bytes(0xFF, 0xF1, 0x50, 0x80)));
    }

    @Test
    void mp4_ftyp_at_offset_4() {
        assertEquals("mp4", MediaMagic.detect(
                bytes(0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0x00, 0x00, 0x02, 0x00)));
    }

    @Test
    void flac_ogg_flv_webm_amr() {
        assertEquals("flac", MediaMagic.detect(bytes('f', 'L', 'a', 'C', 0x00, 0x00, 0x00, 0x22)));
        assertEquals("ogg", MediaMagic.detect(bytes('O', 'g', 'g', 'S', 0x00, 0x02)));
        assertEquals("flv", MediaMagic.detect(bytes('F', 'L', 'V', 0x01, 0x05)));
        assertEquals("webm", MediaMagic.detect(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01)));
        assertEquals("amr", MediaMagic.detect(withAscii(new byte[0], "#!AMR-WB\n")));
    }

    @Test
    void windows_executable_rejected() {
        // PE 头 "MZ" —— spec N6 验收场景：.exe 改名 .mp3 必须拒
        assertNull(MediaMagic.detect(bytes('M', 'Z', 0x90, 0x00, 0x03, 0x00, 0x00, 0x00)));
    }

    @Test
    void zip_png_text_rejected() {
        assertNull(MediaMagic.detect(bytes(0x50, 0x4B, 0x03, 0x04)));              // zip
        assertNull(MediaMagic.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)));  // png
        assertNull(MediaMagic.detect(withAscii(new byte[0], "hello plain text"))); // 文本
    }

    @Test
    void too_short_or_null_rejected() {
        assertNull(MediaMagic.detect(null));
        assertNull(MediaMagic.detect(new byte[0]));
        assertNull(MediaMagic.detect(new byte[]{0x00, 0x00, 0x00}));
    }

    @Test
    void amr_narrowband_opus_container_and_unknown_container() {
        // T3.10：amr 窄带（#!AMR\n，与既有 #!AMR-WB 变体互补）与 opus（OggS 容器）必须识别；
        // 未知容器（随机字节）返回 null，由调用方按 INVALID_MEDIA 拒绝
        assertEquals("amr", MediaMagic.detect(withAscii(new byte[0], "#!AMR\n")));
        assertEquals("ogg", MediaMagic.detect(withAscii(new byte[0], "OggS")));
        assertNull(MediaMagic.detect(new byte[]{(byte) 0xA7, 0x11, (byte) 0xE3, 0x4B,
                0x02, (byte) 0x9D, 0x58, 0x76}));
    }
}
