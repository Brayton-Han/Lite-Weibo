package com.brayton.weibo.event;

import com.brayton.weibo.config.RabbitConfig;
import com.brayton.weibo.entity.Notification;
import com.brayton.weibo.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final RabbitTemplate rabbitTemplate;

    @EventListener
    public void dispatch(Event e) {
        switch (e.getType()) {
            case LIKE -> handleLike((LikeEvent) e);
            case COMMENT -> handleComment((CommentEvent) e);
            case FOLLOW -> handleFollow((FollowEvent) e);
        }
    }

    private void handleFollow(FollowEvent e) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "notification.follow", e);
    }

    private void handleLike(LikeEvent e) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "notification.like", e);
    }

    private void handleComment(CommentEvent e) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "notification.comment", e);
    }
}
