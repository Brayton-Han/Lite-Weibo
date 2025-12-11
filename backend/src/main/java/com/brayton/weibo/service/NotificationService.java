package com.brayton.weibo.service;

import com.brayton.weibo.config.RabbitConfig;
import com.brayton.weibo.entity.Notification;
import com.brayton.weibo.event.CommentEvent;
import com.brayton.weibo.event.Event;
import com.brayton.weibo.event.FollowEvent;
import com.brayton.weibo.event.LikeEvent;
import com.brayton.weibo.repository.NotificationRepository;
import com.brayton.weibo.webSocket.WebSocketPusher;
import com.sun.source.tree.ForLoopTree;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final WebSocketPusher wsPusher;

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
}
