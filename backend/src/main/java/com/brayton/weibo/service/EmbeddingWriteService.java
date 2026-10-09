package com.brayton.weibo.service;

import com.brayton.weibo.config.AsyncConfig;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.event.PostEmbeddingEvent;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 增量向量化（RAG Day 6）：发帖、编辑后让 post_embeddings 与帖子的当前内容保持一致。
 *
 * 独立成 Bean、只经事件触发，原因同 {@link FeedWriteService}：类内自调用会绕过 @Async 代理。
 *
 * <h3>为什么挂在 AFTER_COMMIT 上，并且回库重读</h3>
 * 主事务回滚时不触发，不会为不存在的帖子花 API 费用；异步线程开始执行时帖子一定已提交、可读。
 * 正文和原帖在执行时按 postId 重新查询，而不是由事件携带：排队期间帖子可能被删或改为非公开，
 * 重读能让这些情况自然跳过，且与全量回填走同一条 SQL、同一套拼接规则（{@code PostChunker}）。
 *
 * <h3>新帖与编辑走同一条路径</h3>
 * 不区分「新建」「编辑」，只做一件事：算出当前应有的 chunk_text，与已入库的比对——
 * <ul>
 *   <li>不再需要向量（改为非公开等）：删除旧向量</li>
 *   <li>与已入库的相同（只改了图片、可见性在公开之间无变化）：跳过，不花 API 费用</li>
 *   <li>不同或尚无向量：重新向量化，替换旧向量</li>
 * </ul>
 * 同时处理「转发并评论」了该帖的帖子：它们的 chunk_text 拼着该帖正文，
 * 该帖被编辑或改为非公开后，这些向量也过期了——后者还意味着非公开内容仍能被语义搜索召回。
 *
 * <h3>失败怎么办</h3>
 * 不重试，只记日志。帖子照常可见，只是暂时不能被语义搜索命中（编辑场景下命中的是旧内容）；
 * 漏掉的、过期的、本该删掉的向量，都由重跑 EmbeddingBackfillRunner 修复（它会先做一遍校正）。查漏 SQL 见 RAG_ROADMAP Day 6。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingWriteService {

    private final EmbeddingClient embeddingClient;
    private final PostEmbeddingRepository embeddingRepository;

    @Async(AsyncConfig.EMBEDDING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPostEmbedding(PostEmbeddingEvent e) {
        SyncResult r = syncPost(e.postId());
        log.debug("帖子 {} 向量同步：{}", e.postId(), r);
    }

    /** 同步该帖及引用它的帖子的向量。EmbeddingBackfillRunner 修复过期向量时也走这里。 */
    public SyncResult syncPost(long postId) {
        List<Long> ids = new ArrayList<>();
        ids.add(postId);
        ids.addAll(embeddingRepository.findQuotingPostIds(postId));

        String modelId = embeddingClient.modelId();
        int removed = 0;
        int unchanged = 0;
        List<Long> pendingIds = new ArrayList<>();
        List<String> pendingTexts = new ArrayList<>();

        for (long id : ids) {
            Optional<String> current = embeddingRepository.findChunkText(id);
            if (current.isEmpty()) {
                removed += embeddingRepository.deleteIfIneligible(id);
            } else if (current.equals(embeddingRepository.findStoredChunkText(id, modelId))) {
                unchanged++;
            } else {
                pendingIds.add(id);
                pendingTexts.add(current.get());
            }
        }
        if (pendingIds.isEmpty()) {
            return new SyncResult(0, removed, unchanged, 0, 0);
        }

        List<float[]> vectors;
        try {
            vectors = embeddingClient.embedDocuments(pendingTexts);
        } catch (EmbeddingException ex) {
            // 预期内的外部故障（限流重试耗尽、未配 key 等）：一行日志足够，不需要堆栈
            log.warn("帖子 {} 向量化失败，可由 EmbeddingBackfillRunner 补齐：{}", pendingIds, ex.getMessage());
            return new SyncResult(0, removed, unchanged, 0, pendingIds.size());
        }

        int embedded = 0;
        int superseded = 0;
        for (int i = 0; i < pendingIds.size(); i++) {
            // false：向量化期间帖子又被编辑，交给那次编辑自己的事件
            if (embeddingRepository.replaceIfCurrent(pendingIds.get(i), pendingTexts.get(i), vectors.get(i), modelId)) {
                embedded++;
            } else {
                superseded++;
            }
        }
        return new SyncResult(embedded, removed, unchanged, superseded, 0);
    }

    /**
     * @param embedded   写入了新向量的帖子数
     * @param removed    删除的向量行数
     * @param unchanged  内容未变、跳过的帖子数
     * @param superseded 向量化期间内容又变了、放弃写入的帖子数
     * @param failed     调用 embedding API 失败、没有写入的帖子数
     */
    public record SyncResult(int embedded, int removed, int unchanged, int superseded, int failed) {}
}
