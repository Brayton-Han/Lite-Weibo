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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        assertThat(cache.getPosts(List.of(1L)).get(1L).commentCount()).isEqualTo(7);

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
        // data 里的计数是回填时的值，comment 字段已被 HINCRBY 就地加过
        String json = mapper.writeValueAsString(PostSnapshot.from(post(1, 7)));
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(List.of(
                Map.of("data", json, "comment", "9", "repost", "0"),
                Map.of("missing", "1")));

        Map<Long, PostSnapshot> result = cache.getPosts(List.of(1L, 2L));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L).commentCount()).isEqualTo(9);
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
    void concurrentMissesOnSameIdLoadOnlyOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(postRepository.findByIdInWithUser(anyList())).thenAnswer(inv -> {
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(post(1, 0));
        });

        Thread first = new Thread(() -> cache.getPosts(List.of(1L)));
        first.start();
        verify(postRepository, timeout(5000)).findByIdInWithUser(anyList());

        // 第一个线程卡在查库时，第二个线程来取同一个 id，应等待同一个加载而不是自己回源
        Map<Long, PostSnapshot>[] second = new Map[1];
        Thread t2 = new Thread(() -> second[0] = cache.getPosts(List.of(1L)));
        t2.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (t2.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertThat(t2.getState()).isEqualTo(Thread.State.WAITING);

        release.countDown();
        first.join(5000);
        t2.join(5000);

        assertThat(second[0]).containsKey(1L);
        verify(postRepository, times(1)).findByIdInWithUser(anyList());
    }

    @Test
    void failedLoadIsNotCachedAndPropagates() {
        when(postRepository.findByIdInWithUser(anyList()))
                .thenThrow(new QueryTimeoutException("db down"))
                .thenReturn(List.of(post(1, 0)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.getPosts(List.of(1L)))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(cache.getPosts(List.of(1L))).containsKey(1L);
    }

    @Test
    void adjustCountRunsScriptImmediatelyOutsideTransaction() {
        cache.adjustCount(5L, Counter.COMMENT, -1);

        verify(redis).execute(any(RedisScript.class), eq(List.of("post:5")), eq("comment"), eq("-1"));
    }

    private static Post post(long id, long comments) {
        Post p = new Post();
        p.setId(id);
        p.setUser(new User("u" + id, "x", "u" + id + "@example.com"));
        p.setContent("正文" + id);
        p.setVisibility(PostVisibility.PUBLIC);
        p.setCommentCount(comments);
        p.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return p;
    }
}
