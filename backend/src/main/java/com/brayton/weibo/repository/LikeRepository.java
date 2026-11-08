package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Like;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.enums.PostVisibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LikeRepository extends JpaRepository<Like, Long> {

    @Query("""
    select l.postId from Like l
    where l.userId = :userId
    and l.postId in :postIds
    """)
    Set<Long> findLikedPostIds(Long userId, Collection<Long> postIds);

    Optional<Like> findByUserIdAndPostId(Long userId, Long postId);

    /**
     * 点赞，返回实际插入的行数（已赞过返回 0）。
     * 并发重复点赞时，第二条 INSERT 等第一条提交后撞上唯一约束，ON CONFLICT 让它静默返回 0，
     * 而不是抛 DataIntegrityViolationException 把整个事务回滚、接口报 500。
     * 冲突目标按列写而不按约束名：早期由 Hibernate 建表的库里，唯一约束是自动生成的名字。
     */
    @Modifying
    @Query(value = """
            INSERT INTO likes (user_id, post_id, created_at) VALUES (:userId, :postId, :createdAt)
            ON CONFLICT (user_id, post_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("userId") Long userId, @Param("postId") Long postId,
                              @Param("createdAt") LocalDateTime createdAt);

    // 用户是否点赞了某帖子
    boolean existsByUserIdAndPostId(Long userId, Long postId);

    // 统计帖子点赞数
    Long countByPostId(Long postId);

    /**
     * 删除点赞（用于取消点赞），返回实际删除的行数。
     * 用单条 DELETE 而不是派生的 deleteBy（先 SELECT 再 remove）：并发取消时第二条 DELETE
     * 等到第一条提交后重新判断条件，删掉 0 行，调用方据此只扣一次计数。
     */
    @Modifying
    @Query("delete from Like l where l.userId = :userId and l.postId = :postId")
    int deleteByUserIdAndPostId(@Param("userId") Long userId, @Param("postId") Long postId);

    // 获取某帖子的所有点赞记录（如果你要做“谁点了赞”）
    List<Like> findAllByPostIdOrderByCreatedAtDesc(Long postId);

    void deleteAllByPostId(Long postId); // 删除某个帖子的所有点赞
}