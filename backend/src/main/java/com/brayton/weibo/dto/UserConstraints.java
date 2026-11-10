package com.brayton.weibo.dto;

/** 注册与资料修改共用的字段约束，前端 register / set 页按同一套规则做预校验 */
public final class UserConstraints {

    private UserConstraints() {
    }

    public static final int USERNAME_MIN = 2;
    public static final int USERNAME_MAX = 20;
    /** 任意语言的字母、数字、下划线（允许中文昵称），不允许空格和符号 */
    public static final String USERNAME_PATTERN = "^[\\p{L}\\p{N}_]+$";
    public static final String USERNAME_MESSAGE =
            "username must be 2-20 characters: letters, digits or underscore";

    public static final int PASSWORD_MIN = 8;
    /** BCrypt 只取前 72 字节，再长的部分不参与校验，等于静默截断 */
    public static final int PASSWORD_MAX = 72;
    public static final String PASSWORD_MESSAGE = "password must be 8-72 characters";

    /** RFC 5321 规定的地址上限 */
    public static final int EMAIL_MAX = 254;

    public static final int BIO_MAX = 160;
}
