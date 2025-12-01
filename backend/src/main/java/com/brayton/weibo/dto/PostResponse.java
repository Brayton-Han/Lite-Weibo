package com.brayton.weibo.dto;

import com.brayton.weibo.enums.PostVisibility;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PostResponse {

    private Long id;

    // 作者信息
    private UserResponse user;

    // 帖子内容
    private String content;
    private List<String> images;

    // 可见性
    private PostVisibility visibility;

    // 用户与帖子的关系
    private boolean liked;

    // 统计
    private Long likeCount;
    private Long commentCount;

    // 时间
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private boolean isEdited;
}