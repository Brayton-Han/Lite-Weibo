package com.brayton.weibo.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FollowEvent extends Event {
    private Long followerId;
    private Long followingId;

    @Override
    public EventType getType() {
        return EventType.FOLLOW;
    }
}
