package com.brayton.weibo.event;

import lombok.Getter;

import java.time.LocalDateTime;

@Getter
abstract public class Event {

    private final LocalDateTime timestamp;

    public Event() {
        this.timestamp = LocalDateTime.now();
    }

    abstract public EventType getType();
}
