package com.brayton.weibo.embedding;

/**
 * 决定一条帖子送进 embedding 模型的文本。全量回填（EmbeddingBackfillRunner）与
 * 增量向量化（EmbeddingWriteService）共用这一份规则——两处各写一套的话，
 * 同一条帖子回填和新发会产出不同的向量，检索结果就和数据来源有关了。
 *
 * <h3>chunk_text 怎么拼</h3>
 * 目前一帖一个 chunk（chunk_index = 0）：
 * <ul>
 *   <li>原创帖：正文</li>
 *   <li>转发并评论：评论 + 原帖正文。「转发并评论」的语义由两段共同构成，只 embed 评论
 *       （常常是「好烦啊」这类短句）会丢掉上下文</li>
 *   <li>纯转发（自身正文为空）：跳过。它的内容就是原帖，原帖已有向量，再 embed 一份
 *       只会让同一段文字在检索结果里出现两次</li>
 * </ul>
 * 原帖已删除或非 PUBLIC 时，调用方应传 null，只用评论本身，不把它的内容带出去。
 */
public final class PostChunker {

    private PostChunker() {}

    /** 返回 null 表示该帖不需要向量（纯转发）。 */
    public static String chunkText(String content, String refContent) {
        String own = content == null ? "" : content.strip();
        if (own.isEmpty()) {
            return null;
        }
        String ref = refContent == null ? "" : refContent.strip();
        return ref.isEmpty() ? own : own + "\n\n[转发原文] " + ref;
    }
}
