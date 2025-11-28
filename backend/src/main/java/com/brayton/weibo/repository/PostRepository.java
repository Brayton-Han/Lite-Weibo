package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {
    // 根据作者查帖子（按时间倒序）
    List<Post> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Post> findByUserIdInOrderByCreatedAtDesc(List<Long> userIds);

    // Cursor 分页用：查某用户的帖子，id < cursor，不用 offset，性能稳
    List<Post> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long cursor);

    // Feed 用：全站最新帖子（如果你之后做推荐，这个接口也能先用）
    List<Post> findAllByOrderByIdDesc();

    // Feed Cursor 分页
    List<Post> findByIdLessThanOrderByIdDesc(Long cursor);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
    void incrementLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :postId")
    void incrementCommentCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = CASE WHEN p.commentCount > 0 THEN p.commentCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementCommentCount(@Param("postId") Long postId);
}
