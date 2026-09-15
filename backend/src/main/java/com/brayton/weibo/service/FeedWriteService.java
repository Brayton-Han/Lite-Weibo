package com.brayton.weibo.service;

import com.brayton.weibo.common.TimeUtil;
import com.brayton.weibo.config.AsyncConfig;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.webSocket.WebSocketPusher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * feed 侧的写入操作（扇出、关注预热、点赞索引）。
 *
 * 独立成 Bean 有两个原因：
 *  1. {@code @Async} 依赖 Spring 代理，原先三处都是类内自调用，注解形同虚设；
 *     从外部 Bean 调用才会真正走到代理上。
 *  2. 这些操作只写 Redis + 读关注关系，与 PostService 的查询职责无关。
 *
 * 异步方法一律只接收**原始值**，不接收实体：调用方大多处于 {@code @Transactional}
 * 方法中，实体一旦跨线程，持久化上下文已不可用，对 LAZY 关联（如 {@code Post.user}）
 * 取值会抛 LazyInitializationException。
 *
 * 注意：Redis 不参与数据库事务，主事务回滚时这里写入的索引会残留。该问题在改为
 * 异步之前就存在（同步版同样在事务提交前写 Redis），异步化并未引入新的一致性风险，
 * 根治需配合 AFTER_COMMIT 语义，见分析报告 §6.1-6。
 */
@Service
@RequiredArgsConstructor
public class FeedWriteService {

    private final FollowRepository followRepository;
    private final PostRepository postRepository;
    private final RedisService redisService;
    private final WebSocketPusher wsPusher;

    /**
     * 计算一条帖子应当写入哪些用户的 feed（作者本人 + 按可见性展开的受众）。
     * 实时扇出与缓存回灌复用同一套规则，避免两处漂移。
     */
    public Set<Long> resolveFeedTargets(Long authorId, PostVisibility visibility) {
        Set<Long> pushIds = new HashSet<>();

        // self
        pushIds.add(authorId);

        if (visibility == PostVisibility.FRIENDS) {
            pushIds.addAll(followRepository.findFriendIds(authorId));
        } else if (visibility == PostVisibility.FOLLOWERS) {
            pushIds.addAll(followRepository.findFollowerIds(authorId));
        } else if (visibility == PostVisibility.PUBLIC) {
            // todo: recommend post
            pushIds.addAll(followRepository.findFollowerIds(authorId));
        }

        return pushIds;
    }

    /**
     * 同步扇出。回灌历史帖子时用 notify=false，避免为旧帖重复推送 WebSocket "新帖" 通知。
     *
     * @return 写入的 feed 条目数
     */
    public int fanOutToFeed(Long postId, Long authorId, PostVisibility visibility,
                            long timestamp, boolean notify) {
        Set<Long> pushIds = resolveFeedTargets(authorId, visibility);

        for (Long pushId : pushIds) {
            redisService.addToFeed(pushId, postId, timestamp);
            if (!notify || pushId.equals(authorId)) continue;
            wsPusher.notifyUserNewPost(pushId);
        }

        return pushIds.size();
    }

    /** 回灌入口：在事务内读取实体字段后转交同步扇出，不跨线程传递实体。 */
    public int fanOutExistingPost(Post post) {
        return fanOutToFeed(
                post.getId(),
                post.getUser().getId(),
                post.getVisibility(),
                TimeUtil.toTs(post.getCreatedAt()),
                false
        );
    }

    /** 发帖 / 可见性放宽后的扇出。 */
    @Async(AsyncConfig.FEED_EXECUTOR)
    public void fanOutNewPost(Long postId, Long authorId, PostVisibility visibility, long timestamp) {
        fanOutToFeed(postId, authorId, visibility, timestamp, true);
    }

    /** 新建关注后，把被关注者的近期帖子灌入关注者的 feed。 */
    @Async(AsyncConfig.FEED_EXECUTOR)
    public void warmUpNewFollow(long followerId, long followingId) {
        boolean followed = followRepository.existsByFollowerIdAndFollowingId(followingId, followerId);

        List<Post> posts = postRepository.findNewestPosts(
                Set.of(followingId),
                PostService.visibilityFilter(false, true, followed),
                Long.MAX_VALUE,
                PageRequest.of(0, 20)
        );

        for (Post post : posts) {
            redisService.addToFeed(followerId, post.getId(), TimeUtil.toTs(post.getCreatedAt()));
        }
        redisService.trimFeed(followerId, 1000);
    }

    /** 点赞后写入 liked: 索引。 */
    @Async(AsyncConfig.FEED_EXECUTOR)
    public void recordLiked(Long userId, Long postId, long timestamp) {
        redisService.addToLiked(userId, postId, timestamp);
    }
}
