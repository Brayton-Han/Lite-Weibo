package com.brayton.weibo.event;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 消费端幂等依赖 eventId 在 MQ 往返中保持不变：反序列化时 Event 构造器会先生成一个新 UUID，
 * 必须被消息里的值覆盖，否则每次重投都是"新事件"，唯一约束形同虚设。
 */
class EventSerializationTest {

    private final Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

    @SuppressWarnings("unchecked")
    private <T extends Event> T roundTrip(T event) {
        Message message = converter.toMessage(event, new MessageProperties());
        return (T) converter.fromMessage(message);
    }

    @Test
    void likeEventKeepsEventIdAndTimestamp() {
        LikeEvent sent = new LikeEvent(1L, 2L, 3L);
        LikeEvent received = roundTrip(sent);

        assertEquals(sent.getEventId(), received.getEventId());
        assertEquals(sent.getTimestamp(), received.getTimestamp());
        assertEquals(3L, received.getPostId());
    }

    @Test
    void commentEventKeepsEventId() {
        CommentEvent sent = new CommentEvent(1L, 2L, 3L, "hi");
        assertEquals(sent.getEventId(), roundTrip(sent).getEventId());
    }

    @Test
    void followEventKeepsEventId() {
        FollowEvent sent = new FollowEvent(1L, 2L);
        assertEquals(sent.getEventId(), roundTrip(sent).getEventId());
    }
}
