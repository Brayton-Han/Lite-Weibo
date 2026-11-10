package com.brayton.weibo.service;

import com.brayton.weibo.common.ImageType;
import com.brayton.weibo.common.R2Client;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileServiceTest {

    private final R2Client r2 = mock(R2Client.class);
    private final FileService service = new FileService(r2);

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};

    private static MockMultipartFile file(String name, String declaredType, byte[] content) {
        return new MockMultipartFile("file", name, declaredType, content);
    }

    private static void assertRejected(Runnable call, CommonErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(WeiboException.class,
                e -> assertThat(e.getCode()).isEqualTo(code.getCode()));
    }

    @Test
    void usesSniffedTypeNotClientContentType() {
        // 客户端谎称 text/html，但内容是 PNG：按 PNG 存
        MultipartFile f = file("x.html", "text/html", PNG);
        assertThat(service.validate(f)).isEqualTo(ImageType.PNG);
    }

    @Test
    void acceptsIphoneHeic() {
        assertThat(service.validate(file("IMG_0001.HEIC", "", HEIC))).isEqualTo(ImageType.HEIC);
    }

    @Test
    void rejectsHtmlSvgAndRenamedNonImages() {
        byte[] html = "<!DOCTYPE html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
        byte[] pdf = "%PDF-1.7 not an image".getBytes(StandardCharsets.UTF_8);

        assertRejected(() -> service.validate(file("page.html", "text/html", html)), CommonErrorCode.FILE_TYPE_NOT_ALLOWED);
        assertRejected(() -> service.validate(file("icon.svg", "image/svg+xml", svg)), CommonErrorCode.FILE_TYPE_NOT_ALLOWED);
        // 改名成 .jpg 并谎报 image/jpeg
        assertRejected(() -> service.validate(file("photo.jpg", "image/jpeg", html)), CommonErrorCode.FILE_TYPE_NOT_ALLOWED);
        assertRejected(() -> service.validate(file("photo.png", "image/png", pdf)), CommonErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertRejected(() -> service.validate(file("a.png", "image/png", new byte[0])), CommonErrorCode.FILE_EMPTY);

        byte[] big = new byte[(int) FileService.MAX_FILE_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertRejected(() -> service.validate(file("big.png", "image/png", big)), CommonErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void rejectsWholeBatchBeforeUploadingAnything() {
        List<MultipartFile> batch = List.of(
                file("ok.png", "image/png", PNG),
                file("bad.jpg", "image/jpeg", "<html>".getBytes(StandardCharsets.UTF_8)));

        assertRejected(() -> service.uploadImages(batch), CommonErrorCode.FILE_TYPE_NOT_ALLOWED);
        verify(r2, never()).upload(any(), any());
    }

    @Test
    void uploadsEachFileWithItsDetectedType() {
        MultipartFile png = file("a.png", "image/png", PNG);
        MultipartFile heic = file("b.heic", "image/heic", HEIC);
        when(r2.upload(png, ImageType.PNG)).thenReturn("u1");
        when(r2.upload(heic, ImageType.HEIC)).thenReturn("u2");

        assertThat(service.uploadImages(List.of(png, heic))).containsExactly("u1", "u2");
    }
}
