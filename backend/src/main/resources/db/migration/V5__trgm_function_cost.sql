-- 让规划器知道 word_similarity 很贵，中文搜索才会走 idx_posts_content_trgm（RAG Day 10）。
--
-- pg_trgm 的函数声明的都是默认 COST 1，按「一次简单运算」计价。实际对一条几百字的中文帖子
-- 算一次 word_similarity 约 10 µs：3000 条帖子 seq scan 逐行算 <% 要 ~30 ms，走 GIN 索引只算候选（约 150 条）~2 ms。
-- 但 COST 1 下两者估算成本几乎相同（310 vs 324），custom plan 里 train 40 条 query 有 16 条选了 seq scan，
-- 最慢 37.7 ms；pgjdbc 每条连接的前 5 次执行都是 custom plan，线上搜索会随机遇到。
-- 调到 100 后 40 条全部走索引，最慢 8.5 ms、合计 484 → 50 ms。
--
-- word_similarity_op 是 <% 操作符背后的函数（命中条件），word_similarity 用于 SELECT / ORDER BY 里的打分。
--
-- 注意：这是对扩展成员函数的修改，pg_dump 不会导出它；从 dump 恢复的库要手动重跑本文件
-- （flyway_schema_history 会显示已执行）。重建扩展（DROP / CREATE EXTENSION pg_trgm）同理。

ALTER FUNCTION word_similarity_op(text, text) COST 100;
ALTER FUNCTION word_similarity(text, text) COST 100;
