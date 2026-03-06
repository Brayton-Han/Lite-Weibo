package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Post;
import com.brayton.weibo.enums.PostVisibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("""
        select p from Post p
        join fetch p.user
        where p.id in :ids
    """)
    List<Post> findByIdInWithUser(@Param("ids") Collection<Long> ids);

    int countPostsByUserId(long userId);

    @Query("""
    SELECT p FROM Post p
    WHERE p.user.id IN :userIds
      AND p.visibility IN :visibilities
      AND (:lastId IS NULL OR p.id < :lastId)
    ORDER BY p.id DESC
    """)
    List<Post> findNewestPosts(
            @Param("userIds") Set<Long> userIds,
            @Param("visibilities") List<PostVisibility> visibilities,
            @Param("lastId") Long lastId,
            Pageable pageable
    );

    @Query(value = """
    SELECT *
    FROM posts
    WHERE id < :lastId and visibility in :visibilities and search_vector @@ plainto_tsquery('simple', :query)
    ORDER BY created_at DESC
    """, nativeQuery = true)
    List<Post> searchPosts(
            @Param("query") String query,
            @Param("lastId") Long lastId,
            @Param("visibilities") List<PostVisibility> visibilities,
            Pageable pageable
    );

    @Query("""
    SELECT p
    FROM Post p
    WHERE p.id < :lastId and p.visibility in :visibilities and p.content LIKE :query
    ORDER BY p.createdAt DESC
    """)
    List<Post> searchChinesePosts(
            @Param("query") String query,
            @Param("lastId") Long lastId,
            @Param("visibilities") List<PostVisibility> visibilities,
            Pageable pageable
    );

    List<Post> findByIdIn(List<Long> postIds);

    List<Post> findByIdIn(Set<Object> postIds);

    List<Post> findByIdInOrderByCreatedAtDesc(Set<Object> postIds);

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

    @Modifying
    @Query("UPDATE Post p SET p.repostCount = p.repostCount + 1 WHERE p.id = :postId")
    void incrementRepostCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.repostCount = CASE WHEN p.repostCount > 0 THEN p.repostCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementRepostCount(@Param("postId") Long postId);
}
