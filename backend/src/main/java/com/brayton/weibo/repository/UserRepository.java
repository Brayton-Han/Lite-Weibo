package com.brayton.weibo.repository;

import com.brayton.weibo.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsernameOrEmail(String username, String email);

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
