// src/main/java/com/brayton/weibo/dto/LoginRequest.java
package com.brayton.weibo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String password; // 明文密码
}