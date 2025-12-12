package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.NotificationResponse;
import com.brayton.weibo.event.EventType;
import com.brayton.weibo.service.NotificationService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@AllArgsConstructor
public class NotificationController {

    private NotificationService notificationService;

    @GetMapping("/notification/unread-count")
    public ResponseEntity<ApiResponse<?>> getUnreadCounts(@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(notificationService.getUnreadCounts(user.getId())));
    }

    @PostMapping("/notification/mark-read")
    public ResponseEntity<ApiResponse<?>> markAsRead(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam String type
    ) {
        EventType notifType = EventType.valueOf(type.toUpperCase());
        notificationService.markAllAsRead(user.getId(), notifType);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/notification/list")
    public ResponseEntity<ApiResponse<?>> getNotifications(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam String type,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size
    ) {
        EventType notifType = EventType.valueOf(type.toUpperCase());
        List<NotificationResponse> notifications = notificationService.getNotifications(
                user.getId(), notifType, lastId, size
        );
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }
}
