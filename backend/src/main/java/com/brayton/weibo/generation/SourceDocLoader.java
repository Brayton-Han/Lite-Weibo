package com.brayton.weibo.generation;

import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.service.PostCacheService;
import com.brayton.weibo.service.PostCacheService.PostSnapshot;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索结果 → 送进生成层的 document。返回列表的顺序就是 citation 的 {@code document_index}，
 * {@link CitedAnswerAssembler} 按同一列表把下标映射回 postId。
 *
 * <p>检索与加载之间帖子可能刚被删或改为非公开（与 {@code PostService#getSemanticSearchResults} 同一口径），
 * 这里再滤一次；正文为空的（如纯转发）也跳过，空 document 既没法引用、也可能让请求 400。
 */
@Component
public class SourceDocLoader {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final PostCacheService postCache;

    public SourceDocLoader(PostCacheService postCache) {
        this.postCache = postCache;
    }

    /** @param hits 按相关性从高到低；返回保持这个顺序，可能比输入短 */
    public List<SourceDoc> load(List<ScoredPost> hits) {
        Map<Long, PostSnapshot> posts = postCache.getPosts(hits.stream().map(ScoredPost::postId).toList());
        List<SourceDoc> docs = new ArrayList<>(hits.size());
        Set<Long> seen = new HashSet<>();
        for (ScoredPost hit : hits) {
            PostSnapshot post = posts.get(hit.postId());
            if (post == null || post.visibility() != PostVisibility.PUBLIC
                    || post.content() == null || post.content().isBlank()
                    || !seen.add(hit.postId())) {
                continue;
            }
            docs.add(new SourceDoc(post.id(), title(post), post.content()));
        }
        return docs;
    }

    /** "@作者 · 日期"，会原样出现在 citation 的 document_title 里 */
    static String title(PostSnapshot post) {
        String author = post.author() == null ? "匿名" : post.author().username();
        return post.createdAt() == null ? "@" + author : "@" + author + " · " + post.createdAt().format(DATE);
    }
}
