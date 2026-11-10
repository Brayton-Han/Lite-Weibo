// src/main/java/com/brayton/weibo/dto/LoginRequest.java
package com.brayton.weibo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录只做长度上限，不套注册的格式规则：格式不符的账号本来就登不上，统一按「用户名或密码错误」处理，
 * 不在校验阶段泄露规则。上限防止超长输入进 BCrypt 和限流 key。
 */
@Getter
@Setter
public class LoginRequest {
    @NotBlank(message = "username is required")
    @Size(max = 64, message = "username is too long")
    private String username;

    @NotBlank(message = "password is required")
    @Size(max = 128, message = "password is too long")
    private String password; // 明文密码
}
