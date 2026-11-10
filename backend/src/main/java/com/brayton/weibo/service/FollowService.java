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
import com.brayton.weibo.event.UnfollowEvent;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

        // 通知投递与 feed 预热都监听此事件，均在事务提交后执行
        publisher.publishEvent(new FollowEvent(followerId, followingId));
    }

    @Transactional
    public void unfollow(long followerId, long followingId) {
        if (!followRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            throw new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS);
        }
        followRepository.deleteByFollowerIdAndFollowingId(followerId, followingId);
        userRepository.decrementFollowerCountById(followingId);
        userRepository.decrementFollowCountById(followerId);

        // 收件箱清理在事务提交后异步执行；清理前读路径靠 isVisibleInFeed 兜底过滤
        publisher.publishEvent(new UnfollowEvent(followerId, followingId));
    }

    public List<UserResponse> getFollowers(Long id, Long lastId, int size) {

        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        if (lastId == null) {
            return userService.getUserInfosInOrder(followRepository.findFollowerIds(id, PageRequest.of(0, size)));
        }

        FollowRelation relation = followRepository.findByFollowerIdAndFollowingId(lastId, id)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS));

        return userService.getUserInfosInOrder(
                followRepository.findFollowerIds(id, relation.getId(), PageRequest.of(0, size)));
    }

    public List<UserResponse> getFollowings(Long id, Long lastId, int size) {

        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        if (lastId == null) {
            return userService.getUserInfosInOrder(followRepository.findFollowingIds(id, PageRequest.of(0, size)));
        }

        FollowRelation relation = followRepository.findByFollowerIdAndFollowingId(id, lastId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS));

        return userService.getUserInfosInOrder(
                followRepository.findFollowingIds(id, relation.getId(), PageRequest.of(0, size)));
    }

    /** 好友（互关）列表，与关注列表同样按关系 id keyset 分页；lastId 为上一页最后一个好友的用户 id */
    public List<UserResponse> getFriends(long id, Long lastId, int size) {

        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        if (lastId == null) {
            return userService.getUserInfosInOrder(followRepository.findFriendIds(id, PageRequest.of(0, size)));
        }

        FollowRelation relation = followRepository.findByFollowerIdAndFollowingId(id, lastId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.FOLLOWING_ID_NOT_EXISTS));

        return userService.getUserInfosInOrder(
                followRepository.findFriendIds(id, relation.getId(), PageRequest.of(0, size)));
    }
}
