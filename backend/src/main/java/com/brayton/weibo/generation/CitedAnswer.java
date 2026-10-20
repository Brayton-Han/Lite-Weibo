package com.brayton.weibo.generation;

import java.util.List;

/**
 * 带引用的回答，直接序列化给前端。
 *
 * <p>回答被切成若干 {@link Segment}，按顺序拼起来就是全文 {@link #text}；有引用的段落带 {@link Citation}，
 * 前端在段尾渲染角标 {@code [ref]}，点击按 {@code postId} 跳回原帖。{@link #sources} 是被引用过的帖子，
 * 按首次被引用的顺序排，下标 + 1 即角标编号。没被引用的检索结果不出现在这里。
 */
public record CitedAnswer(String text, List<Segment> segments, List<Source> sources) {

    public record Segment(String text, List<Citation> citations) {}

    /**
     * @param ref       角标编号，从 1 开始，对应 {@code sources.get(ref - 1)}
     * @param citedText 被引用的原文，API 从 document 里逐字截取，不是模型复述的
     * @param startChar 原文在帖子正文里的起始位置（含），UTF-16 下标，前端可直接 {@code slice}；区间无效时为 -1
     * @param endChar   结束位置（不含），同上
     */
    public record Citation(int ref, long postId, String citedText, long startChar, long endChar) {}

    public record Source(int ref, long postId, String title) {}

    public boolean hasCitations() {
        return !sources.isEmpty();
    }
}
