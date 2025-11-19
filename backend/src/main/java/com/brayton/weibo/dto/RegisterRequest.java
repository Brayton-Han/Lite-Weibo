// src/main/java/com/brayton/weibo/dto/RegisterRequest.java
package com.brayton.weibo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

// 建议添加 validation 注解
@Getter
@Setter
public class RegisterRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String password; // 明文密码

    @Email
    @NotBlank
    private String email;
}