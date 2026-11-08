package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Notification;
import com.brayton.weibo.event.EventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 按 event_id 幂等写入通知，返回实际插入的行数（重复投递返回 0）。
     * 消费端没有外层事务，这里自带事务，否则 @Modifying 查询会抛 TransactionRequiredException。
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO notifications (type, actor_id, target_id, post_id, content, read, created_at, event_id)
            VALUES (:#{#n.type.name()}, :#{#n.actorId}, :#{#n.targetId}, :#{#n.postId}, :#{#n.content},
                    :#{#n.read}, :#{#n.createdAt}, :#{#n.eventId})
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("n") Notification n);

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
