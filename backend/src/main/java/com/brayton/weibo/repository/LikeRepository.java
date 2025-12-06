package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Like;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.enums.PostVisibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LikeRepository extends JpaRepository<Like, Long> {

    Optional<Like> findByUserIdAndPostId(Long userId, Long postId);

    // 用户是否点赞了某帖子
    boolean existsByUserIdAndPostId(Long userId, Long postId);

    // 统计帖子点赞数
    Long countByPostId(Long postId);

    // 删除点赞（用于取消点赞）
    void deleteByUserIdAndPostId(Long userId, Long postId);

    // 获取某帖子的所有点赞记录（如果你要做“谁点了赞”）
    List<Like> findAllByPostIdOrderByCreatedAtDesc(Long postId);

    void deleteAllByPostId(Long postId); // 删除某个帖子的所有点赞
}