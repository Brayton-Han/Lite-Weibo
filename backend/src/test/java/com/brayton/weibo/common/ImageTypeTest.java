package com.brayton.weibo.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTypeTest {

    static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    /** ISO BMFF 文件头：4 字节 box 长度 + "ftyp" + 4 字节主品牌 */
    static byte[] ftyp(String brand) {
        byte[] out = new byte[16];
        out[3] = 0x18;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void detectsAllowedFormats() {
        assertThat(ImageType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1))).contains(ImageType.JPEG);
        assertThat(ImageType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D))).contains(ImageType.PNG);
        assertThat(ImageType.detect(ascii("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000"))).contains(ImageType.GIF);
        assertThat(ImageType.detect(ascii("GIF87a\u0001\u0000\u0001\u0000\u0000\u0000"))).contains(ImageType.GIF);
        assertThat(ImageType.detect(ascii("RIFF$\u0000\u0000\u0000WEBPVP8 "))).contains(ImageType.WEBP);
    }

    @Test
    void detectsAppleHeicAndGenericHeif() {
        assertThat(ImageType.detect(ftyp("heic"))).contains(ImageType.HEIC);
        assertThat(ImageType.detect(ftyp("heix"))).contains(ImageType.HEIC);
        assertThat(ImageType.detect(ftyp("hevc"))).contains(ImageType.HEIC);
        assertThat(ImageType.detect(ftyp("mif1"))).contains(ImageType.HEIF);
        assertThat(ImageType.detect(ftyp("msf1"))).contains(ImageType.HEIF);
    }

    @Test
    void rejectsScriptableAndOtherFormats() {
        assertThat(ImageType.detect(ascii("<!DOCTYPE html><html>"))).isEmpty();
        assertThat(ImageType.detect(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\">"))).isEmpty();
        assertThat(ImageType.detect(ascii("<?xml version=\"1.0\"?><svg>"))).isEmpty();
        assertThat(ImageType.detect(ascii("%PDF-1.7\n%...."))).isEmpty();
        assertThat(ImageType.detect(bytes('P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0))).isEmpty();
        // 同为 ISO BMFF，但不是 HEIF：MP4 视频、AVIF
        assertThat(ImageType.detect(ftyp("isom"))).isEmpty();
        assertThat(ImageType.detect(ftyp("avif"))).isEmpty();
        // RIFF 但不是 WebP（WAV 音频）
        assertThat(ImageType.detect(ascii("RIFF$\u0000\u0000\u0000WAVEfmt "))).isEmpty();
    }

    @Test
    void rejectsTruncatedOrEmptyInput() {
        assertThat(ImageType.detect(null)).isEqualTo(Optional.empty());
        assertThat(ImageType.detect(new byte[0])).isEmpty();
        assertThat(ImageType.detect(bytes(0xFF, 0xD8))).isEmpty();
        assertThat(ImageType.detect(ascii("RIFF\u0000\u0000"))).isEmpty();
    }
}
