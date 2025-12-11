package com.brayton.weibo.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LikeEvent extends Event {
    private Long fromUserId;
    private Long toUserId;
    private Long postId;

    @Override
    public EventType getType() {
        return EventType.LIKE;
    }
}
