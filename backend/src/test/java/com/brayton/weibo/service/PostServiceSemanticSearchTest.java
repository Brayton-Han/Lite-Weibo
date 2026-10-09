package com.brayton.weibo.service;

import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.*;
import com.brayton.weibo.repository.PostEmbeddingRepository.Hit;
import com.brayton.weibo.webSocket.WebSocketPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostServiceSemanticSearchTest {

    private static final String MODEL = "voyage/voyage-4";

    private final PostRepository postRepository = mock(PostRepository.class);
    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final PostEmbeddingRepository embeddingRepository = mock(PostEmbeddingRepository.class);
    private final PostService service = new PostService(
            postRepository, mock(UserRepository.class), mock(FollowRepository.class), mock(LikeRepository.class),
            mock(CommentRepository.class), mock(RedisService.class), mock(WebSocketPusher.class),
            mock(ApplicationEventPublisher.class), client, embeddingRepository);

    private final float[] vec = {0.1f};

    @BeforeEach
    void setUp() {
        when(client.modelId()).thenReturn(MODEL);
        when(client.embedQuery(anyString())).thenReturn(vec);
    }

    @Test
    void keepsSimilarityOrderAndAttachesScores() {
        when(embeddingRepository.searchNearest(vec, MODEL, 10))
                .thenReturn(List.of(new Hit(2, 0.9), new Hit(1, 0.5)));
        // 数据库按 id 顺序返回，结果必须恢复成相似度顺序
        when(postRepository.findByIdInWithUser(List.of(2L, 1L)))
                .thenReturn(List.of(post(1, PostVisibility.PUBLIC), post(2, PostVisibility.PUBLIC)));

        var result = service.getSemanticSearchResults("  大雾  ", 10, 99L);

        assertThat(result).extracting(r -> r.getPost().getId()).containsExactly(2L, 1L);
        assertThat(result).extracting(r -> r.getScore()).containsExactly(0.9, 0.5);
        verify(client).embedQuery("大雾");   // 去掉首尾空白再向量化
    }

    @Test
    void dropsPostsDeletedOrHiddenAfterRetrieval() {
        when(embeddingRepository.searchNearest(vec, MODEL, 10))
                .thenReturn(List.of(new Hit(1, 0.9), new Hit(2, 0.8), new Hit(3, 0.7)));
        // 1 刚被删（@Where 滤掉，不在结果里），2 刚改为非公开
        when(postRepository.findByIdInWithUser(anyList()))
                .thenReturn(List.of(post(2, PostVisibility.FRIENDS), post(3, PostVisibility.PUBLIC)));

        var result = service.getSemanticSearchResults("q", 10, 99L);

        assertThat(result).extracting(r -> r.getPost().getId()).containsExactly(3L);
    }

    @Test
    void clampsK() {
        when(embeddingRepository.searchNearest(any(), anyString(), anyInt())).thenReturn(List.of());

        service.getSemanticSearchResults("q", 500, 99L);
        service.getSemanticSearchResults("q", 0, 99L);

        verify(embeddingRepository).searchNearest(vec, MODEL, PostService.SEMANTIC_MAX_K);
        verify(embeddingRepository).searchNearest(vec, MODEL, 1);
    }

    @Test
    void rejectsBlankOrOverlongQueryWithoutCallingApi() {
        assertThatThrownBy(() -> service.getSemanticSearchResults("   ", 10, 99L))
                .isInstanceOf(WeiboException.class)
                .extracting(e -> ((WeiboException) e).getCode())
                .isEqualTo(CommonErrorCode.SEARCH_QUERY_INVALID.getCode());
        assertThatThrownBy(() -> service.getSemanticSearchResults("长".repeat(201), 10, 99L))
                .isInstanceOf(WeiboException.class);

        verify(client, never()).embedQuery(anyString());
    }

    @Test
    void embeddingFailureBecomesUnavailableError() {
        when(client.embedQuery(anyString())).thenThrow(new EmbeddingException("HTTP 429"));

        assertThatThrownBy(() -> service.getSemanticSearchResults("q", 10, 99L))
                .isInstanceOf(WeiboException.class)
                .extracting(e -> ((WeiboException) e).getCode())
                .isEqualTo(CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE.getCode());
    }

    private static Post post(long id, PostVisibility visibility) {
        User author = new User();
        Post p = new Post();
        p.setId(id);
        p.setUser(author);
        p.setVisibility(visibility);
        p.setContent("正文" + id);
        return p;
    }
}
