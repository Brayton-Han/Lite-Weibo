package com.brayton.weibo.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum CommonErrorCode implements ErrorCode {
    USER_NOT_FOUND(1001, "user not found"),
    INVALID_PASSWORD(1002, "invalid password"),
    USER_EXISTS(1003, "username or email already exists"),

    TOKEN_EXPIRED(2001, "Token expired"),

    VALIDATION_FAILED(4001, "validation failed"),
    
    INTERNAL_ERROR(5000, "internal error"),;

    private final int code;
    private final String message;
}