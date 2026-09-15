package com.brayton.weibo.event;

import com.brayton.weibo.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationListener {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 只在业务事务成功提交后才投递通知消息。
     *
     * 原先用 {@code @EventListener}：监听器在 publishEvent 的同一线程、同一事务内
     * 同步执行，于是消息先发、事务后提交。一旦事务回滚（提交期约束冲突、死锁、
     * 连接中断，或调用方被更外层事务包裹），业务数据没落库而通知已经投递出去，
     * 消费端照样落进 notifications 表 —— 用户看到一条并不存在的通知。
     *
     * fallbackExecution = true：若发布事件时根本没有事务上下文，则立即投递。
     * 当前三处发布都在 @Transactional 方法内，这里是为防止日后有人在非事务
     * 路径发布事件时消息被静默丢弃。
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void dispatch(Event e) {
        try {
            switch (e.getType()) {
                case LIKE -> handleLike((LikeEvent) e);
                case COMMENT -> handleComment((CommentEvent) e);
                case FOLLOW -> handleFollow((FollowEvent) e);
            }
        } catch (Exception ex) {
            // AFTER_COMMIT 阶段抛出的异常会传播回调用方，而此时业务事务已经提交，
            // 让用户为一条通知投递失败收到 500 并不合理，这里兜住并记录。
            //
            // 代价是消息可能丢失（投递失败无重试）。问题性质从"可能多发幽灵通知"
            // 变成"可能少发通知"，后者危害小得多；要两头都保证需要 Outbox 表
            // + 定时投递，见分析报告 §6.1-6。
            log.error("通知投递失败，业务事务已提交，该通知将丢失: type={} time={}",
                    e.getType(), e.getTimestamp(), ex);
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
