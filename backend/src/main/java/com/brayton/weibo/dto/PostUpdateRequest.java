package com.brayton.weibo.dto;

import com.brayton.weibo.enums.PostVisibility;
import lombok.Data;

@Data
public class PostUpdateRequest {
    private String content;
    private PostVisibility visibility;
}
