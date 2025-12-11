package com.brayton.weibo.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CommentEvent extends Event {
    private Long fromUserId;
    private Long toUserId;
    private Long postId;
    private String content;

    @Override
    public EventType getType() {
        return EventType.COMMENT;
    }
}
