package com.brayton.weibo.service;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
        log.debug("收到通知 type={}", message.getType());

        Notification n = Notification.follow(message);
        if (saveOnce(n)) {
            wsPusher.notifyUserFollow(message.getFollowingId(), n);
        }
    }

    @RabbitListener(queues = RabbitConfig.LIKE_QUEUE)
    public void onMessage(LikeEvent message) {
        log.debug("收到通知 type={}", message.getType());

        Notification n = Notification.like(message);
        if (saveOnce(n)) {
            wsPusher.notifyUserLike(message.getToUserId(), n);
        }
    }

    @RabbitListener(queues = RabbitConfig.COMMENT_QUEUE)
    public void onMessage(CommentEvent message) {
        log.debug("收到通知 type={}", message.getType());

        Notification n = Notification.comment(message);
        if (saveOnce(n)) {
            wsPusher.notifyUserComment(message.getToUserId(), n);
        }
    }

    /**
     * 幂等落库：RabbitMQ 是至少一次投递，监听方法抛异常重新入队、ack 前连接断开都会让
     * 同一条消息再来一次。按 eventId 去重，重复消息既不落第二条通知，也不再推送。
     * 推送失败抛异常时消息会重投，此时通知已落库，重投只会被跳过 —— 推送是尽力而为，
     * 用户刷新列表仍能看到这条通知。
     */
    private boolean saveOnce(Notification n) {
        if (notificationRepository.insertIgnoreDuplicate(n) == 0) {
            log.info("重复投递的通知已忽略 type={} eventId={}", n.getType(), n.getEventId());
            return false;
        }
        return true;
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
