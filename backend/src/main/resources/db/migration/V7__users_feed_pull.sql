-- 大 V 拉模式（FeedWriteService#usePullMode）：粉丝数达到阈值后，公开/粉丝可见帖只写作者自己的发件箱
-- outbox:{id}，读 feed 时由关注者拉取。这个标记一经置位不再回退：若作者掉粉后切回推模式，
-- 读者就不再读他的发件箱，拉模式期间发的帖会从所有人的 feed 里消失。
ALTER TABLE users ADD COLUMN feed_pull boolean NOT NULL DEFAULT false;
