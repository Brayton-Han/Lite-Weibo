package com.brayton.weibo.dto;

import com.brayton.weibo.enums.PostVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PostUpdateRequest {

    @Size(max = 5000, message = "内容不能超过5000字")
    private String content;

    @Size(max = 9, message = "图片最多9张")
    @NotNull(message = "images 字段必须存在，即使为空数组")
    private List<String> images =  new ArrayList<>();

    @NotNull(message = "可见性不能为空")
    private PostVisibility visibility;
}
