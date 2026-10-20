package com.brayton.weibo.generation;

import com.brayton.weibo.enums.PostType;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.service.PostCacheService;
import com.brayton.weibo.service.PostCacheService.AuthorSnapshot;
import com.brayton.weibo.service.PostCacheService.PostSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class SourceDocLoaderTest {

    private final PostCacheService postCache = mock(PostCacheService.class);
    private final SourceDocLoader loader = new SourceDocLoader(postCache);

    @Test
    void keepsRetrievalOrderAndDropsUnusablePosts() {
        when(postCache.getPosts(anyCollection())).thenReturn(Map.of(
                1L, post(1, "alice", PostVisibility.PUBLIC, "押一付三很常见"),
                2L, post(2, "bob", PostVisibility.PUBLIC, "朝阳押一付一"),
                3L, post(3, "carol", PostVisibility.PRIVATE, "检索后改成了仅自己可见"),
                4L, post(4, "dave", PostVisibility.PUBLIC, " ")));

        // 5 已被删除，缓存里没有；2 重复出现
        List<SourceDoc> docs = loader.load(List.of(
                new ScoredPost(2, 0.9), new ScoredPost(5, 0.8), new ScoredPost(3, 0.7),
                new ScoredPost(1, 0.6), new ScoredPost(4, 0.5), new ScoredPost(2, 0.4)));

        assertEquals(List.of(
                new SourceDoc(2, "@bob · 2026-03-02", "朝阳押一付一"),
                new SourceDoc(1, "@alice · 2026-03-02", "押一付三很常见")), docs);
    }

    private static PostSnapshot post(long id, String username, PostVisibility visibility, String content) {
        AuthorSnapshot author = new AuthorSnapshot(id * 10, username, null, null, null, null, null, 0, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 3, 2, 23, 59);
        return new PostSnapshot(id, author, PostType.ORIGINAL, content, List.of(), null, visibility,
                createdAt, createdAt, false, 0, 0, 0);
    }
}
