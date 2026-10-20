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
