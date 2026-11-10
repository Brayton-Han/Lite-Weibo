package com.brayton.weibo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;

/**
 * 由 Spring 的 ConstraintValidatorFactory 创建，所以能注入配置。
 * 前缀带上结尾的 "/"：否则 https://pub-x.r2.dev.evil.com/... 也能通过 startsWith。
 */
public class R2AvatarUrlValidator implements ConstraintValidator<R2AvatarUrl, String> {

    private final String allowedPrefix;

    public R2AvatarUrlValidator(@Value("${r2.publicBaseUrl}") String publicBaseUrl) {
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
        this.allowedPrefix = base;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) return true;
        return value.startsWith(allowedPrefix) && value.length() > allowedPrefix.length();
    }
}
