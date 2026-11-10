package com.brayton.weibo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 值为 null / 空，或以本站 R2 公网地址（{@code r2.publicBaseUrl}）开头 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = R2AvatarUrlValidator.class)
public @interface R2AvatarUrl {
    String message() default "avatar must be an image uploaded to this site";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
