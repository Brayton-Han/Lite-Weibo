package com.brayton.weibo.service;

import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.service.PostCacheService.Counter;
import com.brayton.weibo.service.PostCacheService.PostSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostCacheServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PostRepository postRepository = mock(PostRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PostCacheService cache = new PostCacheService(postRepository, redis, mapper);

    @Test
    void loadsMissFromDbThenServesFromLocalCache() {
        when(postRepository.findByIdInWithUser(List.of(1L))).thenReturn(List.of(post(1, 7)));

        assertThat(cache.getPosts(List.of(1L))).containsKey(1L);
        assertThat(cache.getPosts(List.of(1L)).get(1L).likeCount()).isEqualTo(7);

        verify(postRepository, times(1)).findByIdInWithUser(anyList());
    }

    @Test
    void cachesNonexistentIdsSoTheyDoNotHitDbAgain() {
        when(postRepository.findByIdInWithUser(anyList())).thenReturn(List.of());

        assertThat(cache.getPosts(List.of(404L))).isEmpty();
        assertThat(cache.getPosts(List.of(404L))).isEmpty();

        verify(postRepository, times(1)).findByIdInWithUser(anyList());
    }

    @Test
    void redisHitSkipsDbAndCounterFieldsOverrideSnapshotJson() throws Exception {
        // data 里的计数是回填时的值，like 字段已被 HINCRBY 就地加过
        String json = mapper.writeValueAsString(PostSnapshot.from(post(1, 7)));
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(List.of(
                Map.of("data", json, "like", "9", "comment", "0", "repost", "0"),
                Map.of("missing", "1")));

        Map<Long, PostSnapshot> result = cache.getPosts(List.of(1L, 2L));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L).likeCount()).isEqualTo(9);
        assertThat(result.get(1L).content()).isEqualTo("正文1");
        verifyNoInteractions(postRepository);
    }

    @Test
    void redisFailureFallsBackToDb() {
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(postRepository.findByIdInWithUser(List.of(1L))).thenReturn(List.of(post(1, 0)));

        assertThat(cache.getPosts(List.of(1L))).containsKey(1L);
    }

    @Test
    void adjustCountRunsScriptImmediatelyOutsideTransaction() {
        cache.adjustCount(5L, Counter.LIKE, -1);

        verify(redis).execute(any(RedisScript.class), eq(List.of("post:5")), eq("like"), eq("-1"));
    }

    private static Post post(long id, long likes) {
        Post p = new Post();
        p.setId(id);
        p.setUser(new User("u" + id, "x", "u" + id + "@example.com"));
        p.setContent("正文" + id);
        p.setVisibility(PostVisibility.PUBLIC);
        p.setLikeCount(likes);
        p.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return p;
    }
}
