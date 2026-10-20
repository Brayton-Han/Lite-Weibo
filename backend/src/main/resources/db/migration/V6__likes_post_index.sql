-- 点赞数改为 Redis 计数后，计数缺失时要按 likes 表重算（LikeCountRepository#countByPostIds），
-- 按 post_id 聚合；此前只有 (user_id, post_id) 唯一索引，前导列不是 post_id，用不上，
-- 每次重算都是一次 likes 全表扫。deleteAllByPostId（删帖）同样受益。
CREATE INDEX idx_likes_post ON likes (post_id);
