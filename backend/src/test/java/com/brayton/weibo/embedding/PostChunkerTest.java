package com.brayton.weibo.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostChunkerTest {

    @Test
    void originalPostUsesOwnContent() {
        assertThat(PostChunker.chunkText("  今天加班到十点  ", null)).isEqualTo("今天加班到十点");
    }

    @Test
    void quotePostAppendsReferencedContent() {
        assertThat(PostChunker.chunkText("好烦啊", "累鼠了")).isEqualTo("好烦啊\n\n[转发原文] 累鼠了");
    }

    @Test
    void pureRepostIsSkipped() {
        assertThat(PostChunker.chunkText(" ", "原帖正文")).isNull();
    }

    @Test
    void hiddenOrDeletedRefFallsBackToOwnContent() {
        // SQL 侧对已删除 / 非 PUBLIC 的原帖返回 NULL
        assertThat(PostChunker.chunkText("看看这个", null)).isEqualTo("看看这个");
    }
}
