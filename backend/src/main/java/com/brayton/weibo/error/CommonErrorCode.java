package com.brayton.weibo.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum CommonErrorCode implements ErrorCode {
    USER_NOT_FOUND(1001, "user not found"),
    INVALID_PASSWORD(1002, "invalid password"),
    USER_EXISTS(1003, "username or email already exists"),
    FOLLOWING_ID_ALREADY_EXISTS(1004, "you have already following the user"),
    FOLLOWING_ID_NOT_EXISTS(1005, "you have not following the user"),
    FOLLOW_YOURSELF(1006, "you can't follow yourself"),
    USERNAME_ALREADY_EXISTS(1007, "username already exists"),

    POST_CONTENT_NULL(1500, "post content is null"),
    POST_NOT_FOUND(1501, "post not found"),
    POST_CANT_DELETE(1502, "can't delete post"),

    COMMENT_NOT_FOUND(1600, "comment not found"),
    COMMENT_CANT_DELETE(1601, "can't delete comment"),

    TOKEN_EXPIRED(2001, "Token expired"),

    VALIDATION_FAILED(4001, "validation failed"),
    FILE_LIST_NULL(4002, "file list is null"),
    PICTURE_MAX_NINE(4003, "you can only upload 9 pictures"),
    R2_UPLOAD_FAILED(4004, "R2 upload failed"),
    
    INTERNAL_ERROR(5000, "internal error"),;

    private final int code;
    private final String message;
}