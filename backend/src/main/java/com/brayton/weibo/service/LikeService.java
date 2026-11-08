package com.brayton.weibo.service;

import com.brayton.weibo.common.TimeUtil;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.event.LikeEvent;
import com.brayton.weibo.repository.LikeRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LikeService {

    private final RedisService redisService;
    private final LikeRepository likeRepository;
    private final PostRepository postRepository;
    private final ApplicationEventPublisher publisher;
    private final UserRepository userRepository;
    private final FeedWriteService feedWriteService;
    private final LikeCountService likeCountService;

    @Transactional
    public void likePost(Long userId, Long postId) {

        // 检查
        Post post = postRepository.findById(postId).orElseThrow(
                () -> new WeiboException(CommonErrorCode.POST_NOT_FOUND)
        );
        if (!userRepository.existsById(userId)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        // 以插入行数为准，而不是先 exists 再 save：并发重复点赞时两边都会通过 exists，
        // 第二个 save 撞唯一约束报错。插入 0 行 = 已经赞过，静默返回，计数、通知都不动
        LocalDateTime likedAt = LocalDateTime.now();
        if (likeRepository.insertIgnoreDuplicate(userId, postId, likedAt) == 0) return;

        // 点赞数走 Redis 计数、异步写回 posts.like_count，不再同步 UPDATE posts（热帖行锁争用）
        likeCountService.adjust(postId, 1);

        if (!userId.equals(post.getUser().getId())) {
            publisher.publishEvent(new LikeEvent(userId, post.getUser().getId(), postId));
        }

        // 跨 Bean 调用才会走 @Async 代理；只传原始值
        feedWriteService.recordLiked(userId, postId, TimeUtil.toTs(likedAt));
    }

    @Transactional
    public void unlikePost(Long userId, Long postId) {

        // 以 DELETE 的影响行数为准，而不是先 exists 再删：并发取消时两边都会通过 exists，计数会被多扣一次
        if (likeRepository.deleteByUserIdAndPostId(userId, postId) == 0) return;

        likeCountService.adjust(postId, -1);

        // 同步清理 liked: ZSet，否则会残留读路径无法解释的幽灵条目
        redisService.removeFromLiked(userId, postId);
    }
}
