package com.brayton.weibo.generation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ClaudeGatewayTest {

    private static final Headers HEADERS = Headers.builder().put("request-id", "req_test").build();
    private static final JsonValue BODY = JsonValue.from(Map.of());

    private final AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
    private final ClaudeGateway gateway = new ClaudeGateway(client);

    private static final MessageCreateParams PARAMS = MessageCreateParams.builder()
            .model("claude-haiku-5-5").maxTokens(16L).addUserMessage("hi").build();

    @Test
    void mapsEachSdkErrorToItsKind() {
        assertKind(NotFoundException.builder().headers(HEADERS).body(BODY).build(), ClaudeCallException.Kind.CONFIG, false);
        assertKind(UnauthorizedException.builder().headers(HEADERS).body(BODY).build(), ClaudeCallException.Kind.CONFIG, false);
        assertKind(RateLimitException.builder().headers(HEADERS).body(BODY).build(), ClaudeCallException.Kind.RATE_LIMITED, true);
        assertKind(InternalServerException.builder().statusCode(500).headers(HEADERS).body(BODY).build(),
                ClaudeCallException.Kind.UPSTREAM, true);
        // 529 overloaded 没有具名子类，按状态码归上游
        assertKind(UnexpectedStatusCodeException.builder().statusCode(529).headers(HEADERS).body(BODY).build(),
                ClaudeCallException.Kind.UPSTREAM, true);
        assertKind(BadRequestException.builder().headers(HEADERS).body(BODY).build(), ClaudeCallException.Kind.BAD_REQUEST, false);
        assertKind(new AnthropicIoException("connection reset"), ClaudeCallException.Kind.NETWORK, true);
        // OkHttp 的 callTimeout / 读超时
        assertKind(new AnthropicIoException("Stream failed", new java.io.InterruptedIOException("timeout")),
                ClaudeCallException.Kind.TIMEOUT, true);
        assertKind(new AnthropicIoException("Request failed", new java.net.SocketTimeoutException("Read timed out")),
                ClaudeCallException.Kind.TIMEOUT, true);
    }

    @Test
    void reportsUsageToListenersAndMetrics() throws Exception {
        java.util.List<ClaudeGateway.CallUsage> seen = new java.util.ArrayList<>();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ClaudeGateway observed = new ClaudeGateway(client, meters, java.util.List.of(seen::add, u -> {
            throw new IllegalStateException("记账失败不能让调用失败");
        }));
        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message());

        observed.create("answer", PARAMS);

        assertEquals(java.util.List.of(new ClaudeGateway.CallUsage("answer", "claude-haiku-5-5", 340, 15, 1000, 0)), seen);
        assertEquals(15, meters.counter("claude.tokens", "purpose", "answer", "type", "output").count());
        assertEquals(1, meters.timer("claude.calls", "purpose", "answer", "outcome", "ok").count());
        assertEquals(0.0, meters.get("claude.prompt.cache.hit.ratio").gauge().value());
    }

    @Test
    void perCallTimeoutIsPassedAsRequestOptions() throws Exception {
        when(client.messages().create(any(MessageCreateParams.class), any(com.anthropic.core.RequestOptions.class)))
                .thenReturn(message());

        gateway.create("intent", PARAMS, java.time.Duration.ofSeconds(8));

        verify(client.messages()).create(eq(PARAMS), argThat((com.anthropic.core.RequestOptions o) ->
                o.getTimeout() != null && o.getTimeout().request().equals(java.time.Duration.ofSeconds(8))));
    }

    private static Message message() throws Exception {
        return ObjectMappers.jsonMapper().readValue("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":340,"output_tokens":15,
                          "cache_creation_input_tokens":1000,"cache_read_input_tokens":0}}
                """, Message.class);
    }

    @Test
    void accumulatesUsageOnSuccess() throws Exception {
        Message message = ObjectMappers.jsonMapper().readValue("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":340,"output_tokens":15,
                          "cache_creation_input_tokens":1000,"cache_read_input_tokens":0}}
                """, Message.class);
        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message);

        gateway.create("test", PARAMS);
        gateway.create("test", PARAMS);

        assertEquals(new ClaudeGateway.UsageTotals(2, 680, 30, 2000, 0), gateway.totals());
    }

    @Test
    void failedCallsDoNotCountUsage() {
        when(client.messages().create(any(MessageCreateParams.class)))
                .thenThrow(RateLimitException.builder().headers(HEADERS).body(BODY).build());
        assertThrows(ClaudeCallException.class, () -> gateway.create("test", PARAMS));
        assertEquals(new ClaudeGateway.UsageTotals(0, 0, 0, 0, 0), gateway.totals());
    }

    private void assertKind(RuntimeException sdkError, ClaudeCallException.Kind kind, boolean retryable) {
        reset(client);
        when(client.messages().create(any(MessageCreateParams.class))).thenThrow(sdkError);
        ClaudeCallException e = assertThrows(ClaudeCallException.class, () -> gateway.create("test", PARAMS));
        assertEquals(kind, e.kind(), sdkError.getClass().getSimpleName());
        assertEquals(retryable, e.retryable());
        assertSame(sdkError, e.getCause());
    }
}
