-- RAG 评测语料的分层元数据（Day 3 Step 4）。
--
-- 为什么是独立表而不是 posts 的新列：
--   层/簇/族只服务于 Day 8 的分层标注与 Day 9 的分层报数，生产代码从不读它。
--   加到 posts 上会让每个 DTO、每次序列化都带一个只有评测才用的字段。
--
-- 真实用户发的帖子在此表中没有记录——它们本就不属于评测语料。
-- 查询时用 LEFT JOIN，缺失即表示「非语料帖」，不要用它来推断层。

CREATE TABLE corpus_meta (
    post_id    bigint      PRIMARY KEY REFERENCES posts(id) ON DELETE CASCADE,
    gen_id     varchar(32) NOT NULL,
    layer      varchar(16) NOT NULL,
    cluster_id varchar(8),              -- 噪声层为 NULL
    family     varchar(64),             -- 噪声层为 NULL
    source     varchar(16),             -- 靶区为 NULL；噪声层为 'LWC'
    source_id  varchar(64),             -- LWC 的 message_id，CC BY-NC-SA 署名溯源用
    CONSTRAINT ck_corpus_meta_layer CHECK (layer IN ('experience', 'resonance', 'noise'))
);

CREATE INDEX idx_corpus_meta_layer   ON corpus_meta (layer);
CREATE INDEX idx_corpus_meta_cluster ON corpus_meta (cluster_id) WHERE cluster_id IS NOT NULL;
