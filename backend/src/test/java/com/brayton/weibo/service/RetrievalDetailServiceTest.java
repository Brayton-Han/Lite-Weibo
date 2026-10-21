package com.brayton.weibo.service;

import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.RetrievalDetailResponse;
import com.brayton.weibo.dto.RetrievalDetailResponse.FusedHit;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostLexicalSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalDetailServiceTest {

    private final PostLexicalSearchRepository lexicalRepository = mock(PostLexicalSearchRepository.class);
    private final PostEmbeddingRepository embeddingRepository = mock(PostEmbeddingRepository.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final PostService postService = mock(PostService.class);
    // n=20、rrfK=10、w=10，即 Day 11 选定配置
    private final RetrievalDetailService service = new RetrievalDetailService(
            lexicalRepository, embeddingRepository, embeddingClient, postService, 20, 10, 10.0, 0.40, 10);

    @BeforeEach
    void setUp() {
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1f});
        when(embeddingClient.modelId()).thenReturn("voyage/voyage-4");
        when(postService.getPublicPosts(anyList(), any())).thenReturn(List.of(post(1, "押一付三是行规"), post(3, "中介费")));
    }

    @Test
    void fusedRowsCarryEachPathsRankAndContribution() {
        when(lexicalRepository.searchChinese("押一付三", null, 20)).thenReturn(List.of(
                new PostLexicalSearchRepository.Hit(3, 0.9), new PostLexicalSearchRepository.Hit(1, 0.5)));
        when(embeddingRepository.searchNearest(any(), anyString(), eq(20))).thenReturn(List.of(
                new PostEmbeddingRepository.Hit(1, 0.62), new PostEmbeddingRepository.Hit(2, 0.55)));

        RetrievalDetailResponse r = service.explain(" 押一付三 ", 5, 7L);

        assertThat(r.lexicalMode()).isEqualTo("trigram");
        assertThat(r.vectorTop1()).isEqualTo(0.62);
        // 帖子 1：词面第 2、向量第 1 → 1/12 + 10/11，两路都有，排第一
        FusedHit top = r.fused().get(0);
        assertThat(top.postId()).isEqualTo(1);
        assertThat(top.lexicalRank()).isEqualTo(2);
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(top.score()).isCloseTo(1.0 / 12 + 10.0 / 11, within(1e-9));
        assertThat(top.lexicalContribution() + top.vectorContribution()).isCloseTo(top.score(), within(1e-9));
        assertThat(top.snippet()).isEqualTo("押一付三是行规");
        // 只在向量路的帖子 2（10/12）排在只在词面路的帖子 3（1/11）前面——w=10 的效果
        assertThat(r.fused().stream().map(FusedHit::postId).toList()).containsExactly(1L, 2L, 3L);
        FusedHit vectorOnly = r.fused().get(1);
        assertThat(vectorOnly.lexicalRank()).isNull();
        assertThat(vectorOnly.lexicalContribution()).isZero();
        // 取不到的帖子（2 没在 getPublicPosts 里）保留名次，没有摘要
        assertThat(vectorOnly.snippet()).isNull();
    }

    @Test
    void panelShowsTopKButFusesOverDepthN() {
        List<PostLexicalSearchRepository.Hit> lexical = java.util.stream.LongStream.rangeClosed(1, 20)
                .mapToObj(i -> new PostLexicalSearchRepository.Hit(i, 1.0 / i)).toList();
        when(lexicalRepository.searchEnglish("rent", null, 20)).thenReturn(lexical);
        when(embeddingRepository.searchNearest(any(), anyString(), eq(20)))
                .thenReturn(List.of(new PostEmbeddingRepository.Hit(20, 0.5)));

        RetrievalDetailResponse r = service.explain("rent", 3, 7L);

        assertThat(r.lexicalMode()).isEqualTo("ts_rank_cd");
        assertThat(r.lexical()).hasSize(3);
        // 词面第 20 名因为向量第 1 名被拉到融合第一——面板只列词面 top-3，但融合看的是深度 20
        assertThat(r.fused().get(0).postId()).isEqualTo(20);
        assertThat(r.fused().get(0).lexicalRank()).isEqualTo(20);
    }

    @Test
    void embeddingFailureIsReportedAsSemanticSearchUnavailable() {
        when(lexicalRepository.searchChinese(anyString(), isNull(), anyInt())).thenReturn(List.of());
        when(embeddingClient.embedQuery(anyString())).thenThrow(new EmbeddingException("429"));

        assertThatThrownBy(() -> service.explain("押一付三", 5, 7L)).isInstanceOf(WeiboException.class);
    }

    @Test
    void rejectsInvalidQuery() {
        assertThatThrownBy(() -> service.explain(" ", 5, 7L)).isInstanceOf(WeiboException.class);
        verifyNoInteractions(lexicalRepository, embeddingClient);
    }

    @Test
    void snippetTruncatesByCodePoint() {
        String longText = "😤".repeat(RetrievalDetailService.SNIPPET_LENGTH + 5);
        String s = RetrievalDetailService.snippet(post(1, longText));
        assertThat(s.codePointCount(0, s.length())).isEqualTo(RetrievalDetailService.SNIPPET_LENGTH + 1);
        assertThat(s).endsWith("…");
    }

    private static PostResponse post(long id, String content) {
        return PostResponse.builder().id(id).content(content).build();
    }
}
