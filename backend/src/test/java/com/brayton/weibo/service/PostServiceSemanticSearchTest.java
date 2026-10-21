package com.brayton.weibo.service;

import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.*;
import com.brayton.weibo.retrieval.RetrievalStrategy;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.webSocket.WebSocketPusher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** {@link SemanticSearchService} 与它依赖的 {@link PostService#toScoredResponses} */
class PostServiceSemanticSearchTest {

    private final PostRepository postRepository = mock(PostRepository.class);
    private final RetrievalStrategy strategy = mock(RetrievalStrategy.class);
    private final PostService postService = new PostService(
            postRepository, mock(UserRepository.class), mock(FollowRepository.class), mock(LikeRepository.class),
            mock(CommentRepository.class), mock(RedisService.class), mock(WebSocketPusher.class),
            mock(ApplicationEventPublisher.class),
            mock(PostLexicalSearchRepository.class),
            // Redis 为 mock：管道读返回空，等价于缓存全部未命中，走 findByIdInWithUser
            new PostCacheService(postRepository, mock(StringRedisTemplate.class),
                    new ObjectMapper().findAndRegisterModules()),
            // getCounts 返回空 map，点赞数按快照里的 DB 副本兜底
            mock(LikeCountService.class));
    private final SemanticSearchService service = new SemanticSearchService(strategy, postService);

    @Test
    void keepsRetrievalOrderAndAttachesScores() {
        when(strategy.retrieve("大雾", 10, 99L))
                .thenReturn(List.of(new ScoredPost(2, 0.9), new ScoredPost(1, 0.5)));
        // 数据库按 id 顺序返回，结果必须恢复成检索顺序
        when(postRepository.findByIdInWithUser(List.of(2L, 1L)))
                .thenReturn(List.of(post(1, PostVisibility.PUBLIC), post(2, PostVisibility.PUBLIC)));

        var result = service.search("  大雾  ", 10, 99L);

        assertThat(result).extracting(r -> r.getPost().getId()).containsExactly(2L, 1L);
        assertThat(result).extracting(r -> r.getScore()).containsExactly(0.9, 0.5);
    }

    @Test
    void dropsPostsDeletedOrHiddenAfterRetrieval() {
        when(strategy.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(new ScoredPost(1, 0.9), new ScoredPost(2, 0.8), new ScoredPost(3, 0.7)));
        // 1 刚被删（@Where 滤掉，不在结果里），2 刚改为非公开
        when(postRepository.findByIdInWithUser(anyList()))
                .thenReturn(List.of(post(2, PostVisibility.FRIENDS), post(3, PostVisibility.PUBLIC)));

        var result = service.search("q", 10, 99L);

        assertThat(result).extracting(r -> r.getPost().getId()).containsExactly(3L);
    }

    @Test
    void clampsK() {
        when(strategy.retrieve(anyString(), anyInt(), any())).thenReturn(List.of());

        service.search("q", 500, 99L);
        service.search("q", 0, 99L);

        verify(strategy).retrieve("q", SemanticSearchService.MAX_K, 99L);
        verify(strategy).retrieve("q", 1, 99L);
    }

    @Test
    void rejectsBlankOrOverlongQueryWithoutRetrieving() {
        assertThatThrownBy(() -> service.search("   ", 10, 99L))
                .isInstanceOf(WeiboException.class)
                .extracting(e -> ((WeiboException) e).getCode())
                .isEqualTo(CommonErrorCode.SEARCH_QUERY_INVALID.getCode());
        assertThatThrownBy(() -> service.search("长".repeat(201), 10, 99L))
                .isInstanceOf(WeiboException.class);

        verifyNoInteractions(strategy);
    }

    @Test
    void embeddingFailureBecomesUnavailableError() {
        when(strategy.retrieve(anyString(), anyInt(), any())).thenThrow(new EmbeddingException("HTTP 429"));

        assertThatThrownBy(() -> service.search("q", 10, 99L))
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
