package com.brayton.weibo.repository;

import com.brayton.weibo.entity.FollowRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FollowRepository extends JpaRepository<FollowRelation, Long> {
    List<FollowRelation> findByFollowerId(long id);
    List<FollowRelation> findByFollowingId(long id);
    boolean existsByFollowerIdAndFollowingId(long followerId, long followingId);
    void deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);
}
