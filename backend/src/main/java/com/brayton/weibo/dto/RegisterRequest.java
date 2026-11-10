// src/main/java/com/brayton/weibo/dto/RegisterRequest.java
package com.brayton.weibo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import static com.brayton.weibo.dto.UserConstraints.*;

@Getter
@Setter
public class RegisterRequest {
    @NotBlank(message = USERNAME_MESSAGE)
    @Size(min = USERNAME_MIN, max = USERNAME_MAX, message = USERNAME_MESSAGE)
    @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE)
    private String username;

    @NotBlank(message = PASSWORD_MESSAGE)
    @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = PASSWORD_MESSAGE)
    private String password; // 明文密码

    @NotBlank(message = "email is required")
    @Email(message = "invalid email")
    @Size(max = EMAIL_MAX, message = "email is too long")
    private String email;
}
