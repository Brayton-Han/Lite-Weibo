package com.brayton.weibo.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostEmbeddingRepositoryTest {

    @Test
    void formatsPgVectorLiteral() {
        assertThat(PostEmbeddingRepository.toPgVector(new float[]{0.5f, -1f, 1.0E-5f}))
                .isEqualTo("[0.5,-1.0,1.0E-5]");
    }
}
