package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByTargetIdOrderByCreatedAtDesc(Long targetId); // 用户打开通知页

    long countByTargetIdAndReadFalse(Long targetId); // 顶部小红点（未读计数）
}
