package com.brayton.weibo.dto;

import com.brayton.weibo.enums.Gender;
import com.brayton.weibo.validation.R2AvatarUrl;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

import static com.brayton.weibo.dto.UserConstraints.*;

/**
 * PUT /set 的请求体。原来直接复用 {@link UserResponse}，客户端能塞进任意字段、任意长度、任意外链头像。
 * 字段为 null 表示不修改。
 */
@Getter
@Setter
public class UpdateUserRequest {

    @Size(min = USERNAME_MIN, max = USERNAME_MAX, message = USERNAME_MESSAGE)
    @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE)
    private String username;

    @Size(max = BIO_MAX, message = "bio must be at most 160 characters")
    private String bio;

    /** 只允许本站 R2 上传返回的地址，防止外链（跟踪像素、不良内容、混合内容） */
    @R2AvatarUrl
    private String avatarUrl;

    private Gender gender;

    @PastOrPresent(message = "birthday cannot be in the future")
    private LocalDate birthday;
}
