package com.brayton.weibo.repository;

import com.brayton.weibo.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsernameOrEmail(String username, String email);

    /** 用户列表首页：粉丝数降序，同粉丝数按 id 降序保证顺序稳定（keyset 分页的前提）。索引见 V9 */
    @Query("""
        select u
        from User u
        order by u.followerCount desc, u.id desc
    """)
    List<User> findUsersPage(Pageable pageable);

    /** 用户列表后续页：从游标 (followerCount, id) 之后继续 */
    @Query("""
        select u
        from User u
        where u.followerCount < :followerCount
           or (u.followerCount = :followerCount and u.id < :id)
        order by u.followerCount desc, u.id desc
    """)
    List<User> findUsersPageAfter(@Param("followerCount") int followerCount,
                                  @Param("id") long id,
                                  Pageable pageable);

    /** 转为拉模式（单向，见 V7 迁移）；在扇出的异步线程里调用，自带事务 */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.feedPull = true WHERE u.id = :id AND u.feedPull = false")
    int markFeedPull(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.followCount = u.followCount + 1 WHERE u.id = :id")
    void incrementFollowCountById(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.followCount = u.followCount - 1 WHERE u.id = :id")
    void decrementFollowCountById(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.followerCount = u.followerCount + 1 WHERE u.id = :id")
    void incrementFollowerCountById(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.followerCount = u.followerCount - 1 WHERE u.id = :id")
    void decrementFollowerCountById(@Param("id") long id);
}
