// src/main/java/com/brayton/weibo/dto/UserResponse.java
package com.brayton.weibo.dto;

import com.brayton.weibo.entity.User;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class UserResponse {
    private long id;
    private String username;
    private String email;
    private String avatarUrl;
    private String bio;
    private LocalDateTime createdAt;

    // 构造函数，用于将 User 实体转换为 Response DTO
    public UserResponse(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.avatarUrl = user.getAvatarUrl();
        this.bio = user.getBio();
        this.createdAt = user.getCreatedAt();
    }

    // 默认构造函数
    public UserResponse() {}
}