package com.brayton.weibo.error;

public class WeiboException extends RuntimeException {

    private final int code;

    public WeiboException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
