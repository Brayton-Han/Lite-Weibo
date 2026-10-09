package com.brayton.weibo.service;

import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostEmbeddingRepository.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbeddingWriteServiceTest {

    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final PostEmbeddingRepository repo = mock(PostEmbeddingRepository.class);
    private final EmbeddingWriteService service = new EmbeddingWriteService(client, repo);

    @Test
    void embedsAndWritesEligiblePost() {
        float[] vec = {0.1f, 0.2f};
        when(repo.findChunkText(7L)).thenReturn(Optional.of("今天加班到十点"));
        when(client.embedDocuments(List.of("今天加班到十点"))).thenReturn(List.of(vec));
        when(client.modelId()).thenReturn("voyage/voyage-4");

        assertThat(service.embedPost(7L)).isTrue();

        verify(repo).insertAll(eq(List.of(new Chunk(7L, "今天加班到十点", vec))), eq("voyage/voyage-4"));
    }

    @Test
    void skipsIneligiblePostWithoutCallingApi() {
        // 已删除 / 非 PUBLIC / 纯转发，由 SQL + PostChunker 判定为空
        when(repo.findChunkText(8L)).thenReturn(Optional.empty());

        assertThat(service.embedPost(8L)).isFalse();

        verifyNoInteractions(client);
        verify(repo, never()).insertAll(anyList(), any());
    }

    @Test
    void apiFailureIsSwallowedAndNothingWritten() {
        when(repo.findChunkText(9L)).thenReturn(Optional.of("x"));
        when(client.embedDocuments(anyList())).thenThrow(new EmbeddingException("HTTP 429"));

        assertThat(service.embedPost(9L)).isFalse();

        verify(repo, never()).insertAll(anyList(), any());
    }
}
