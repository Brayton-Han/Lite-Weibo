// src/main/java/com/brayton/weibo/dto/LoginRequest.java
package com.brayton.weibo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
    @NotBlank
    private String username; // 或者用 email, 实际应用中通常是二者之一

    @NotBlank
    private String password; // 明文密码
}