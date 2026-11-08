package com.brayton.weibo.entity;

import com.brayton.weibo.event.CommentEvent;
import com.brayton.weibo.event.EventType;
import com.brayton.weibo.event.FollowEvent;
import com.brayton.weibo.event.LikeEvent;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;
    private Long targetId;

    @Enumerated(EnumType.STRING)
    private EventType type;

    private Long postId;        // like/comment
    private String content;     // comment content

    private Boolean read = false;

    private LocalDateTime createdAt;

    /** 来源事件 id，唯一约束，用于消费端幂等 */
    private UUID eventId;

    // ---- 工厂方法（推荐） ----
    public static Notification follow(FollowEvent e) {
        Notification n = new Notification();
        n.actorId = e.getFollowerId();
        n.targetId = e.getFollowingId();
        n.type = EventType.FOLLOW;
        n.createdAt = e.getTimestamp();
        n.eventId = e.getEventId();
        return n;
    }

    public static Notification like(LikeEvent e) {
        Notification n = new Notification();
        n.actorId = e.getFromUserId();
        n.targetId = e.getToUserId();
        n.type = EventType.LIKE;
        n.postId = e.getPostId();
        n.createdAt = e.getTimestamp();
        n.eventId = e.getEventId();
        return n;
    }

    public static Notification comment(CommentEvent e) {
        Notification n = new Notification();
        n.actorId = e.getFromUserId();
        n.targetId = e.getToUserId();
        n.type = EventType.COMMENT;
        n.postId = e.getPostId();
        n.content = e.getContent();
        n.createdAt = e.getTimestamp();
        n.eventId = e.getEventId();
        return n;
    }
}
