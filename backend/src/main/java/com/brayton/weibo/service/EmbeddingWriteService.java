package com.brayton.weibo.service;

import com.brayton.weibo.config.AsyncConfig;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.event.PostEmbeddingEvent;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostEmbeddingRepository.Chunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * 增量向量化（RAG Day 6）：新帖提交后异步生成向量，写入 post_embeddings。
 *
 * 独立成 Bean、只经事件触发，原因同 {@link FeedWriteService}：类内自调用会绕过 @Async 代理。
 *
 * <h3>为什么挂在 AFTER_COMMIT 上，并且回库重读</h3>
 * 主事务回滚时不触发，不会为不存在的帖子花 API 费用；异步线程开始执行时帖子一定已提交、可读。
 * 正文和原帖在执行时按 postId 重新查询，而不是由事件携带：排队期间帖子可能被删或改为非公开，
 * 重读能让这些情况自然跳过，且与全量回填走同一条 SQL、同一套拼接规则（{@code PostChunker}）。
 *
 * <h3>失败怎么办</h3>
 * 不重试，只记日志。帖子照常可见，只是暂时不能被语义搜索命中；
 * 漏掉的帖子由 EmbeddingBackfillRunner 补齐（它只选「当前模型下还没有向量」的帖子）。
 * 查漏 SQL 见 RAG_ROADMAP Day 6。
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
        embedPost(e.postId());
    }

    /** @return 是否写入了向量；不需要向量或调用失败时为 false */
    boolean embedPost(long postId) {
        Optional<String> text = embeddingRepository.findChunkText(postId);
        if (text.isEmpty()) {
            return false;   // 已删除 / 非 PUBLIC / 纯转发
        }

        float[] vector;
        try {
            vector = embeddingClient.embedDocuments(List.of(text.get())).get(0);
        } catch (EmbeddingException ex) {
            // 预期内的外部故障（限流重试耗尽、未配 key 等）：一行日志足够，不需要堆栈
            log.warn("帖子 {} 向量化失败，可由 EmbeddingBackfillRunner 补齐：{}", postId, ex.getMessage());
            return false;
        }

        embeddingRepository.insertAll(List.of(new Chunk(postId, text.get(), vector)), embeddingClient.modelId());
        return true;
    }
}
