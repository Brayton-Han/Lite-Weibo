package com.brayton.weibo.service;

import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.service.EmbeddingWriteService.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbeddingWriteServiceTest {

    private static final String MODEL = "voyage/voyage-4";

    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final PostEmbeddingRepository repo = mock(PostEmbeddingRepository.class);
    private final EmbeddingWriteService service = new EmbeddingWriteService(client, repo);

    @BeforeEach
    void setUp() {
        when(client.modelId()).thenReturn(MODEL);
        when(repo.findQuotingPostIds(anyLong())).thenReturn(List.of());
        when(repo.findStoredChunkText(anyLong(), eq(MODEL))).thenReturn(Optional.empty());
        when(repo.replaceIfCurrent(anyLong(), anyString(), any(), eq(MODEL))).thenReturn(true);
    }

    @Test
    void newPostIsEmbedded() {
        float[] vec = {0.1f, 0.2f};
        when(repo.findChunkText(7L)).thenReturn(Optional.of("今天加班到十点"));
        when(client.embedDocuments(List.of("今天加班到十点"))).thenReturn(List.of(vec));

        assertThat(service.syncPost(7L)).isEqualTo(new SyncResult(1, 0, 0, 0));

        verify(repo).replaceIfCurrent(7L, "今天加班到十点", vec, MODEL);
    }

    @Test
    void editedContentIsReEmbedded() {
        float[] vec = {0.3f};
        when(repo.findChunkText(7L)).thenReturn(Optional.of("改过的正文"));
        when(repo.findStoredChunkText(7L, MODEL)).thenReturn(Optional.of("原来的正文"));
        when(client.embedDocuments(List.of("改过的正文"))).thenReturn(List.of(vec));

        assertThat(service.syncPost(7L)).isEqualTo(new SyncResult(1, 0, 0, 0));

        verify(repo).replaceIfCurrent(7L, "改过的正文", vec, MODEL);
    }

    @Test
    void unchangedContentSkipsApi() {
        // 只改了图片：chunk_text 与入库的相同
        when(repo.findChunkText(7L)).thenReturn(Optional.of("正文"));
        when(repo.findStoredChunkText(7L, MODEL)).thenReturn(Optional.of("正文"));

        assertThat(service.syncPost(7L)).isEqualTo(new SyncResult(0, 0, 1, 0));

        verify(client, never()).embedDocuments(anyList());
    }

    @Test
    void postNoLongerPublicHasVectorsRemoved() {
        when(repo.findChunkText(8L)).thenReturn(Optional.empty());
        when(repo.deleteIfIneligible(8L)).thenReturn(1);

        assertThat(service.syncPost(8L)).isEqualTo(new SyncResult(0, 1, 0, 0));

        verify(client, never()).embedDocuments(anyList());
    }

    @Test
    void quotingPostsAreResyncedInOneBatch() {
        // 原帖 10 被编辑，转发并评论它的 11 的 chunk_text 随之变化
        float[] v10 = {1f};
        float[] v11 = {2f};
        when(repo.findQuotingPostIds(10L)).thenReturn(List.of(11L));
        when(repo.findChunkText(10L)).thenReturn(Optional.of("新原文"));
        when(repo.findChunkText(11L)).thenReturn(Optional.of("好烦啊\n\n[转发原文] 新原文"));
        when(client.embedDocuments(List.of("新原文", "好烦啊\n\n[转发原文] 新原文")))
                .thenReturn(List.of(v10, v11));

        assertThat(service.syncPost(10L)).isEqualTo(new SyncResult(2, 0, 0, 0));

        verify(client, times(1)).embedDocuments(anyList());
        verify(repo).replaceIfCurrent(11L, "好烦啊\n\n[转发原文] 新原文", v11, MODEL);
    }

    @Test
    void contentChangedDuringEmbeddingIsNotWritten() {
        when(repo.findChunkText(7L)).thenReturn(Optional.of("v1"));
        when(client.embedDocuments(anyList())).thenReturn(List.of(new float[]{0f}));
        when(repo.replaceIfCurrent(eq(7L), eq("v1"), any(), eq(MODEL))).thenReturn(false);

        assertThat(service.syncPost(7L)).isEqualTo(new SyncResult(0, 0, 0, 1));
    }

    @Test
    void apiFailureIsSwallowedAndNothingWritten() {
        when(repo.findChunkText(9L)).thenReturn(Optional.of("x"));
        when(client.embedDocuments(anyList())).thenThrow(new EmbeddingException("HTTP 429"));

        assertThat(service.syncPost(9L)).isEqualTo(new SyncResult(0, 0, 0, 0));

        verify(repo, never()).replaceIfCurrent(anyLong(), anyString(), any(), anyString());
    }
}
