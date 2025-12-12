package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Notification;
import com.brayton.weibo.event.EventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
        SELECT n FROM Notification n
        WHERE n.targetId = :userId AND n.type = :type
        ORDER BY n.createdAt DESC
    """)
    List<Notification> getNotifications(
            @Param("userId") Long userId,
            @Param("type") EventType type,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM Notification n
        WHERE n.targetId = :userId AND n.type = :type AND n.id < :lastId
        ORDER BY n.createdAt DESC
    """)
    List<Notification> getNotifications(
            @Param("userId") Long userId,
            @Param("type") EventType type,
            @Param("lastId") Long lastId,
            Pageable pageable
    );

    int countByTargetIdAndReadFalseAndType(Long targetId, EventType type); // 顶部小红点（未读计数）

    @Modifying
    @Query("""
        UPDATE Notification n SET n.read = true
        WHERE n.targetId = :targetId
        AND n.type = :type
        AND n.read = false
    """)
    void markAllRead(@Param("targetId") Long targetId,
                    @Param("type") EventType type);
}
