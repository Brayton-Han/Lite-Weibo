package com.brayton.weibo.service;

import com.brayton.weibo.common.R2Client;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileService {

    private final R2Client r2Client;

    public FileService(R2Client r2Client) {
        this.r2Client = r2Client;
    }

    public List<String> uploadImages(List<MultipartFile> files) {
        return files.stream()
                .map(r2Client::upload)
                .collect(Collectors.toList());
    }
}
