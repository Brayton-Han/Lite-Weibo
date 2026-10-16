package com.brayton.weibo.retrieval;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * 现状基线：原样调用线上搜索用的 {@code searchChinesePosts} / {@code searchPosts}，
 * 分支条件与参数同 {@code PostService#getSearchResults}，<b>包括 {@code ORDER BY created_at DESC}</b>——
 * 命中的帖子按时间排而不是按相关性排，这正是要被量化的缺陷，不能顺手修掉。
 *
 * 没有相关性分数，score 一律记 0。
 */
@RequiredArgsConstructor
public class LikeStrategy implements RetrievalStrategy {

    private static final List<PostVisibility> PUBLIC = List.of(PostVisibility.PUBLIC);

    private final PostRepository postRepository;

    @Override
    public String name() {
        return "like";
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        List<Post> posts = ChineseUtil.containsChinese(query)
                ? postRepository.searchChinesePosts("%" + query + "%", Long.MAX_VALUE, PUBLIC, PageRequest.of(0, k))
                : postRepository.searchPosts(query, Long.MAX_VALUE, PUBLIC, PageRequest.of(0, k));
        return posts.stream().map(p -> new ScoredPost(p.getId(), 0.0)).toList();
    }
}
