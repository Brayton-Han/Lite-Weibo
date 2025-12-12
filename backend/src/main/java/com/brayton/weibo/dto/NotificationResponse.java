package com.brayton.weibo.dto;

import com.brayton.weibo.event.EventType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponse {
    Long id;
    EventType type;
    UserResponse sender;
    Long postId;
    String postPreview;
    String commentContent;
    LocalDateTime createdAt;
    Boolean read;
}
