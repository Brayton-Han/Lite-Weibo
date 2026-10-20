package com.brayton.weibo.generation;

/**
 * 送进生成层的一条检索结果。在 prompt 里的位置就是响应里 citation 的 {@code document_index}，
 * 调用方按同一顺序保留 postId，引用才能跳回原帖。
 *
 * @param title 展示给模型的标题，如"@作者 · 2026-03-01"；会原样出现在 citation 的 {@code document_title}
 * @param text  帖子正文，不可信的用户数据
 */
public record SourceDoc(long postId, String title, String text) {}
