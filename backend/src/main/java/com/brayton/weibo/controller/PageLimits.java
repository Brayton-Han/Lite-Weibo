package com.brayton.weibo.controller;

/**
 * 分页参数（size / k）的统一上限。超出不报错，截断到上限；非正数按 1 处理。
 * 防止 size=100000 这类请求一次拉全表、放大 N+1 查询或打满下游（向量检索 / rerank）。
 */
public final class PageLimits {

    public static final int MAX_PAGE_SIZE = 20;

    private PageLimits() {
    }

    public static int clamp(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }
}
