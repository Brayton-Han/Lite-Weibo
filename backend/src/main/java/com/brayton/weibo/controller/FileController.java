package com.brayton.weibo.controller;

import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<?>> uploadImages(
            @RequestParam("file") List<MultipartFile> files
    ) {
        System.out.println("File upload start...");
        // 空文件列表（前端会避免，但保险）
        if (files == null || files.isEmpty()) {
            throw new WeiboException(CommonErrorCode.FILE_LIST_NULL);
        }

        // 最大 9 张（你微博项目规定的）
        if (files.size() > 9) {
            throw new WeiboException(CommonErrorCode.PICTURE_MAX_NINE);
        }

        // 核心上传逻辑
        List<String> urls = fileService.uploadImages(files);

        return ResponseEntity.ok(ApiResponse.success(urls));
    }
}
