package com.brayton.weibo.service;

import com.brayton.weibo.entity.FollowRelation;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.ErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FollowService {
    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final UserService userService;

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

    public List<String> getFollowers(long id) {
        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }
        List<FollowRelation> idList = followRepository.findByFollowingId(id);
        List<String> followers = new ArrayList<>();
        for (FollowRelation followRelation : idList) {
            followers.add(userService.getUsernameById(followRelation.getFollowerId()));
        }
        return followers;
    }

    public List<String> getFollowings(long id) {
        if (!userRepository.existsById(id)) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }
        List<FollowRelation> idList = followRepository.findByFollowerId(id);
        List<String> followings = new ArrayList<>();
        for (FollowRelation followRelation : idList) {
            followings.add(userService.getUsernameById(followRelation.getFollowingId()));
        }
        return followings;
    }
}
