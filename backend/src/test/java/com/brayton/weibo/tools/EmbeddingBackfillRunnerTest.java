package com.brayton.weibo.tools;

import com.brayton.weibo.tools.EmbeddingBackfillRunner.Embedded;
import com.brayton.weibo.tools.EmbeddingBackfillRunner.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingBackfillRunnerTest {

    @Test
    void freshVectorIsOk() {
        assertThat(EmbeddingBackfillRunner.classify(new Embedded(1, "正文", null, true, "正文")))
                .isEqualTo(Verdict.OK);
    }

    @Test
    void deletedOrHiddenPostIsOrphan() {
        // eligible = false：已删除或非 PUBLIC
        assertThat(EmbeddingBackfillRunner.classify(new Embedded(2, "正文", null, false, "正文")))
                .isEqualTo(Verdict.ORPHAN);
    }

    @Test
    void editedPostIsStale() {
        assertThat(EmbeddingBackfillRunner.classify(new Embedded(3, "新正文", null, true, "旧正文")))
                .isEqualTo(Verdict.STALE);
    }

    @Test
    void quoteOfDeletedPostIsStale() {
        // 原帖被删后 SQL 侧 ref_content 为 NULL，入库文本里还拼着原帖正文
        assertThat(EmbeddingBackfillRunner.classify(new Embedded(4, "好烦啊", null, true, "好烦啊\n\n[转发原文] 原文")))
                .isEqualTo(Verdict.STALE);
    }

    @Test
    void missingCurrentModelVectorIsLeftToBackfill() {
        // 只有其他模型的向量：属于「缺失」，不在校正阶段逐条重算
        assertThat(EmbeddingBackfillRunner.classify(new Embedded(5, "正文", null, true, null)))
                .isEqualTo(Verdict.OK);
    }
}
