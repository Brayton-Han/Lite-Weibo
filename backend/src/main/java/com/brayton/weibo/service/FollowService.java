package com.brayton.weibo.service;

import com.brayton.weibo.common.TimeUtil;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.FollowRelation;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.ErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.event.FollowEvent;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final UserService userService;
    private final RedisService redisService;
    private final ApplicationEventPublisher publisher;

    @Async
    public void newFollowPostWarmUp(long followerId, long followingId) {

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

    @Transactional
    public void follow(long followerId, long followingId) {

        if (followerId == followingId) {
            throw new WeiboException(CommonErrorCode.FOLLOW_YOURSELF);
        }
        if (followRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            throw new WeiboException(CommonErrorCode.FOLLOWING_ID_ALREADY_EXISTS);
        }
        followRepository.save(new FollowRelation(followerId, followingId));
        userRepository.incrementFollowerCountById(followingId);
        userRepository.incrementFollowCountById(followerId);

        publisher.publishEvent(new FollowEvent(followerId, followingId));

        // warm-up
        newFollowPostWarmUp(followerId, followingId);
    }

    @Transactional
    public void unfollow(long followerId, long followingId) {
        if (!followRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            throw new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS);
        }
        followRepository.deleteByFollowerIdAndFollowingId(followerId, followingId);
        userRepository.decrementFollowerCountById(followingId);
        userRepository.decrementFollowCountById(followerId);
    }

    public List<UserResponse> getFollowers(Long id, Long lastId, int size) {

        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        if (lastId == null) {
            return followRepository.findFollowerIds(id, PageRequest.of(0, size))
                    .stream()
                    .map(userService::getUserInfoById)
                    .toList();
        }

        FollowRelation relation = followRepository.findByFollowerIdAndFollowingId(lastId, id)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS));

        return followRepository.findFollowerIds(id, relation.getId(), PageRequest.of(0, size))
                .stream()
                .map(userService::getUserInfoById)
                .toList();
    }

    public List<UserResponse> getFollowings(Long id, Long lastId, int size) {

        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        if (lastId == null) {
            return followRepository.findFollowingIds(id, PageRequest.of(0, size))
                    .stream()
                    .map(userService::getUserInfoById)
                    .toList();
        }

        FollowRelation relation = followRepository.findByFollowerIdAndFollowingId(id, lastId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS));

        return followRepository.findFollowingIds(id, relation.getId(), PageRequest.of(0, size))
                .stream()
                .map(userService::getUserInfoById)
                .toList();
    }

    public List<UserResponse> getFriends(long id) {

        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return followRepository.findFriendIds(id)
                .stream()
                .map(userService::getUserInfoById)
                .toList();
    }
}
