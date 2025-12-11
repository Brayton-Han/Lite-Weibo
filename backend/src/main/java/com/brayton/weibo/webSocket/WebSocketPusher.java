package com.brayton.weibo.webSocket;

import com.brayton.weibo.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebSocketPusher {

    private final SimpMessagingTemplate messagingTemplate;

    public void notifyUserFollow(Long userId, Notification follow) {
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/follow",
                follow
        );
    }

    public void notifyUserLike(Long userId, Notification like) {
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/like",
                like
        );
    }

    public void notifyUserComment(Long userId, Notification comment) {
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/comment",
                comment
        );
    }
}
