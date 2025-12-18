package com.brayton.weibo.common;

public class ChineseUtil {
    static public boolean containsChinese(String s) {
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
