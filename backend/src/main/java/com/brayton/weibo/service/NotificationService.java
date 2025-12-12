package com.brayton.weibo.service;

import com.brayton.weibo.config.RabbitConfig;
import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.NotificationResponse;
import com.brayton.weibo.dto.UnreadCountResponse;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.Notification;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.event.*;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.NotificationRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import com.brayton.weibo.webSocket.WebSocketPusher;
import com.sun.source.tree.ForLoopTree;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final WebSocketPusher wsPusher;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final PostRepository postRepository;

    @RabbitListener(queues = RabbitConfig.FOLLOW_QUEUE)
    public void onMessage(FollowEvent message) {
        System.out.println("收到通知：" + message.getType());

        Notification n = Notification.follow(message);
        notificationRepository.save(n);
        wsPusher.notifyUserFollow(message.getFollowingId(), n);
    }

    @RabbitListener(queues = RabbitConfig.LIKE_QUEUE)
    public void onMessage(LikeEvent message) {
        System.out.println("收到通知：" + message.getType());

        Notification n = Notification.like(message);
        notificationRepository.save(n);
        wsPusher.notifyUserLike(message.getToUserId(), n);
    }

    @RabbitListener(queues = RabbitConfig.COMMENT_QUEUE)
    public void onMessage(CommentEvent message) {
        System.out.println("收到通知：" + message.getType());

        Notification n = Notification.comment(message);
        notificationRepository.save(n);
        wsPusher.notifyUserComment(message.getToUserId(), n);
    }

    public UnreadCountResponse getUnreadCounts(Long userId) {

        return UnreadCountResponse.builder()
                .follow(notificationRepository.countByTargetIdAndReadFalseAndType(userId, EventType.FOLLOW))
                .like(notificationRepository.countByTargetIdAndReadFalseAndType(userId, EventType.LIKE))
                .comment(notificationRepository.countByTargetIdAndReadFalseAndType(userId, EventType.COMMENT))
                .build();
    }

    @Transactional
    public void markAllAsRead(Long targetId, EventType type) {
        notificationRepository.markAllRead(targetId, type);
    }

    public List<NotificationResponse> getNotifications(Long userId, EventType type, Long lastId, int size) {

        List<Notification> notifications = (lastId == null) ?
                        notificationRepository.getNotifications(userId, type, PageRequest.of(0, size)) :
                        notificationRepository.getNotifications(userId, type, lastId, PageRequest.of(0, size));

        List<NotificationResponse> responses = new ArrayList<>();
        for (Notification notification : notifications) {

            User sender = userRepository.findById(notification.getActorId()).orElseThrow(
                    () -> new WeiboException(CommonErrorCode.USER_NOT_FOUND)
            );
            if (notification.getType() ==  EventType.FOLLOW) {
                responses.add(NotificationResponse.builder()
                        .id(notification.getId())
                        .type(type)
                        .sender(new UserResponse(sender, false, false, 0, 0))
                        .createdAt(notification.getCreatedAt())
                        .build());
                continue;
            }

            Post post = postRepository.findById(notification.getPostId()).orElseThrow(
                    () -> new WeiboException(CommonErrorCode.POST_NOT_FOUND)
            );

            responses.add(NotificationResponse.builder()
                    .id(notification.getId())
                    .type(type)
                    .sender(new UserResponse(sender, false, false, 0, 0))
                    .postId(notification.getPostId())
                    .postPreview(post.getImages().isEmpty() ? post.getContent() : post.getImages().getFirst())
                    .commentContent(notification.getContent())
                    .createdAt(notification.getCreatedAt())
                    .read(notification.getRead())
                    .build());
        }

        return responses;
    }
}
