-- GET /users 按 (follower_count DESC, id DESC) 做 keyset 分页，配套索引避免每页全表排序
CREATE INDEX IF NOT EXISTS idx_users_follower_count_id ON users (follower_count DESC, id DESC);
