package com.brayton.weibo.service;

import com.brayton.weibo.common.ImageType;
import com.brayton.weibo.common.R2Client;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class FileService {

    /** 单张上限；spring.servlet.multipart.max-file-size 也设为同值，这里在业务层再兜一次（配置可能被改大） */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final R2Client r2Client;

    public FileService(R2Client r2Client) {
        this.r2Client = r2Client;
    }

    /** 先全部校验再上传：任何一张不合格整批拒绝，避免前几张已经进了 R2 */
    public List<String> uploadImages(List<MultipartFile> files) {
        List<ImageType> types = files.stream().map(this::validate).toList();
        List<String> urls = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            urls.add(r2Client.upload(files.get(i), types.get(i)));
        }
        return urls;
    }

    /** 校验大小并按文件头识别真实类型；客户端的 Content-Type 和文件名一律不看 */
    ImageType validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new WeiboException(CommonErrorCode.FILE_EMPTY);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new WeiboException(CommonErrorCode.FILE_TOO_LARGE);
        }
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(ImageType.SNIFF_LENGTH);
        } catch (IOException e) {
            throw new WeiboException(CommonErrorCode.R2_UPLOAD_FAILED);
        }
        return ImageType.detect(head).orElseThrow(() -> {
            log.info("拒绝上传：文件头不是允许的图片类型 name={} declared={} size={}",
                    file.getOriginalFilename(), file.getContentType(), file.getSize());
            return new WeiboException(CommonErrorCode.FILE_TYPE_NOT_ALLOWED);
        });
    }
}
