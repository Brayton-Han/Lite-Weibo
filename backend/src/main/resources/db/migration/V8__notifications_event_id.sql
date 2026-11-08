-- 通知消费幂等：事件在发布端生成 event_id 随消息投递，消费端按它 INSERT ... ON CONFLICT DO NOTHING。
-- RabbitMQ 至少一次投递（监听抛异常后重新入队、ack 前连接断开等）会让同一条消息被消费多次，
-- 没有这个约束就会落多条通知、推送多次。
-- 列允许为 NULL：存量通知没有 event_id，PostgreSQL 唯一索引里多个 NULL 互不冲突。
ALTER TABLE notifications ADD COLUMN event_id uuid;
CREATE UNIQUE INDEX uk_notifications_event_id ON notifications (event_id);
