package com.brayton.weibo.event;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
abstract public class Event {

    /**
     * 事件唯一标识，在发布端生成并随消息序列化。消费端据此去重：
     * 同一条消息被 RabbitMQ 重复投递时 eventId 不变，notifications.event_id 唯一约束只放行第一次。
     * 反序列化时构造器先生成一个新值，再被消息里的值覆盖（与 timestamp 相同）。
     */
    private final UUID eventId;
    private final LocalDateTime timestamp;

    public Event() {
        this.eventId = UUID.randomUUID();
        this.timestamp = LocalDateTime.now();
    }

    abstract public EventType getType();
}
