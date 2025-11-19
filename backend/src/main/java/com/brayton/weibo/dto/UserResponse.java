// src/main/java/com/brayton/weibo/dto/UserResponse.java
package com.brayton.weibo.dto;

import com.brayton.weibo.entity.Gender;
import com.brayton.weibo.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private Gender gender;
    private String avatarUrl;
    private String bio;
    private LocalDate birthday;
    private LocalDate joinDate;

    // 构造函数，用于将 User 实体转换为 Response DTO
    public UserResponse(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.gender = user.getGender();
        this.avatarUrl = user.getAvatarUrl();
        this.bio = user.getBio();
        this.birthday = user.getBirthday();
        this.joinDate = user.getCreatedAt().toLocalDate();
    }
}