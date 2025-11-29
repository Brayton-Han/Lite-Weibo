package com.brayton.weibo.repository;

import com.brayton.weibo.entity.FollowRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

public interface FollowRepository extends JpaRepository<FollowRelation, Long> {

    boolean existsByFollowerIdAndFollowingId(long followerId, long followingId);

    void deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);

    @Query("SELECT f.followingId FROM FollowRelation f WHERE f.followerId = :userId")
    Set<Long> findFollowingIds(Long userId);

    @Query("SELECT f.followerId FROM FollowRelation f WHERE f.followingId = :userId")
    Set<Long> findFollowerIds(Long userId);

    @Query("""
    SELECT f.followingId
    FROM FollowRelation f
    WHERE f.followerId = :userId
      AND EXISTS (
          SELECT 1 FROM FollowRelation f2
          WHERE f2.followerId = f.followingId
            AND f2.followingId = :userId
      )
""")
    Set<Long> findFriendIds(Long userId);

    @Query("""
    SELECT COUNT(*)
    FROM FollowRelation f
    WHERE f.followerId = :userId
      AND EXISTS (
          SELECT 1 FROM FollowRelation f2
          WHERE f2.followerId = f.followingId
            AND f2.followingId = :userId
      )
""")
    int findFriendCountIds(Long userId);
}
