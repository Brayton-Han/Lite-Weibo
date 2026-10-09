-- 编辑帖子后要找出「转发并评论」了它的帖子重新向量化（EmbeddingWriteService），
-- 按 ref_post_id 查询；此前没有这列的索引，每次编辑都是一次 posts 全表扫。
-- 部分索引：原创帖的 ref_post_id 为 NULL，不进索引。
CREATE INDEX idx_posts_ref_post ON posts (ref_post_id) WHERE ref_post_id IS NOT NULL;
