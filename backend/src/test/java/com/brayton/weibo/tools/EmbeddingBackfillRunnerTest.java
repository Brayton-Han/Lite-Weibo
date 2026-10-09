package com.brayton.weibo.tools;

import com.brayton.weibo.tools.EmbeddingBackfillRunner.Pending;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingBackfillRunnerTest {

    @Test
    void originalPostUsesOwnContent() {
        assertThat(EmbeddingBackfillRunner.chunkText(new Pending(1, "  今天加班到十点  ", null)))
                .isEqualTo("今天加班到十点");
    }

    @Test
    void quotePostAppendsReferencedContent() {
        assertThat(EmbeddingBackfillRunner.chunkText(new Pending(2, "好烦啊", "累鼠了")))
                .isEqualTo("好烦啊\n\n[转发原文] 累鼠了");
    }

    @Test
    void pureRepostIsSkipped() {
        assertThat(EmbeddingBackfillRunner.chunkText(new Pending(3, " ", "原帖正文"))).isNull();
    }

    @Test
    void hiddenOrDeletedRefFallsBackToOwnContent() {
        // SQL 侧对已删除 / 非 PUBLIC 的原帖返回 NULL
        assertThat(EmbeddingBackfillRunner.chunkText(new Pending(4, "看看这个", null))).isEqualTo("看看这个");
    }

    @Test
    void formatsPgVectorLiteral() {
        assertThat(EmbeddingBackfillRunner.toPgVector(new float[]{0.5f, -1f, 1.0E-5f}))
                .isEqualTo("[0.5,-1.0,1.0E-5]");
    }
}
