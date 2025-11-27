package com.brayton.weibo.dto;

import com.brayton.weibo.entity.PostVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreatePostRequest {
    // 帖子内容（必须有内容或至少有一张图）
    @Size(max = 5000, message = "内容不能超过5000字")
    private String content;

    // 图片 URL 列表
    @Size(max = 9, message = "图片最多9张")
    private List<String> images;

    // 可见性
    @NotBlank(message = "可见性不能为空")
    private PostVisibility visibility = PostVisibility.PUBLIC;
}
