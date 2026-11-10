package com.brayton.weibo.common;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 允许上传的图片类型，按文件头魔数识别，不信任客户端的 Content-Type 和文件名。
 * 存进 R2 的 Content-Type 与扩展名都取这里的规范值，所以 .html / .svg 改名成 .jpg 也会被拒。
 *
 * <p>HEIC / HEIF：iPhone 默认格式。前端会先尝试转成 JPEG（heic2any），转换失败时原文件上传，这里放行；
 * 注意除 Safari 外多数浏览器不能直接显示 HEIC。
 */
public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    HEIC("image/heic", "heic"),
    HEIF("image/heif", "heif");

    /** 识别需要的最少字节数（ISO BMFF 的 ftyp 品牌在第 8-11 字节，WebP 标记在第 8-11 字节） */
    public static final int SNIFF_LENGTH = 12;

    /** HEVC 编码的 HEIF，即苹果的 .heic */
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis");
    /** 通用 HEIF 品牌 */
    private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1");

    private final String mimeType;
    private final String extension;

    ImageType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    /** 按文件头识别；不在白名单内（含 SVG、HTML、AVIF 等）返回 empty */
    public static Optional<ImageType> detect(byte[] head) {
        if (head == null) return Optional.empty();
        if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(JPEG);
        if (startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of(PNG);
        if (ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a")) return Optional.of(GIF);
        if (ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) return Optional.of(WEBP);
        if (ascii(head, 4, "ftyp") && head.length >= 12) {
            String brand = new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (HEIC_BRANDS.contains(brand)) return Optional.of(HEIC);
            if (HEIF_BRANDS.contains(brand)) return Optional.of(HEIF);
        }
        return Optional.empty();
    }

    public static String allowedList() {
        return String.join(", ", Arrays.stream(values()).map(t -> t.extension).toList());
    }

    private static boolean startsWith(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] data, int offset, String expected) {
        if (data.length < offset + expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (data[offset + i] != (byte) expected.charAt(i)) return false;
        }
        return true;
    }
}
