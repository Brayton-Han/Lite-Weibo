package com.brayton.weibo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "follows",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"follower_id", "following_id"})
        })
@Getter
@NoArgsConstructor
public class FollowRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long followerId;

    @Column(nullable = false)
    private Long followingId;

    private LocalDateTime createdAt = LocalDateTime.now();

    public FollowRelation(Long followerId, Long followingId) {
        this.followerId = followerId;
        this.followingId = followingId;
    }
}
