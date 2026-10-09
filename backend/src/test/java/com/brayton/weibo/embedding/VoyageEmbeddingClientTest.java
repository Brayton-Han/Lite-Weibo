package com.brayton.weibo.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

/** 不连网：用 MockRestServiceServer 验证报文、拆批、顺序还原与重试策略。 */
class VoyageEmbeddingClientTest {

    private static final String URL = "https://voyage.test/v1/embeddings";
    private static final int DIM = 3;

    private MockRestServiceServer server;
    private List<Long> sleeps;
    private VoyageEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://voyage.test");
        server = MockRestServiceServer.bindTo(builder).build();
        sleeps = new ArrayList<>();
        client = new VoyageEmbeddingClient(builder, "test-key", "voyage-4", DIM, 2, 3, sleeps::add);
    }

    @Test
    void queryUsesQueryInputTypeAndBearerKey() {
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andExpect(jsonPath("$.model").value("voyage-4"))
                .andExpect(jsonPath("$.output_dimension").value(DIM))
                .andExpect(jsonPath("$.truncation").value(false))
                .andRespond(withSuccess(response(0), MediaType.APPLICATION_JSON));

        assertThat(client.embedQuery("怎么实现分布式锁")).containsExactly(0f, 0.5f, 1f);
        server.verify();
    }

    @Test
    void documentsAreSplitIntoBatchesAndKeepInputOrder() {
        // batchSize = 2，5 条应拆成 2 + 2 + 1 三个请求，且都是 document
        server.expect(jsonPath("$.input.length()").value(2)).andExpect(jsonPath("$.input_type").value("document"))
                .andRespond(withSuccess(response(0, 1), MediaType.APPLICATION_JSON));
        // 第二批故意乱序返回，客户端必须按 index 还原
        server.expect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess("""
                        {"object":"list","data":[%s,%s],"model":"voyage-4","usage":{"total_tokens":4}}
                        """.formatted(item(1, 3), item(0, 2)), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(response(4), MediaType.APPLICATION_JSON));

        List<float[]> out = client.embedDocuments(List.of("a", "b", "c", "d", "e"));

        assertThat(out).hasSize(5);
        IntStream.range(0, 5).forEach(i -> assertThat(out.get(i)[0]).isEqualTo((float) i));
        server.verify();
    }

    @Test
    void retries429HonoringRetryAfterThenSucceeds() {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.RETRY_AFTER, "7");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(response(0), MediaType.APPLICATION_JSON));

        client.embedQuery("q");

        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isEqualTo(7_000L);               // 以 Retry-After 为准
        assertThat(sleeps.get(1)).isBetween(1_000L, 2_000L);       // 第 2 次：2s × [0.5, 1.0)
        server.verify();
    }

    @Test
    void rateLimitWithoutRetryAfterWaitsPastTheMinuteWindow() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(response(0), MediaType.APPLICATION_JSON));

        client.embedQuery("q");

        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(20_000L, 30_000L);     // 20s × [1.0, 1.5)
        assertThat(sleeps.get(1)).isBetween(40_000L, 60_000L);     // 40s × [1.0, 1.5)
        server.verify();
    }

    @Test
    void givesUpAfterMaxAttempts() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        }
        assertThatThrownBy(() -> client.embedQuery("q"))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("502");
        server.verify();
    }

    @Test
    void doesNotRetryClientErrors() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.embedQuery("q"))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("401");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void rejectsDimensionMismatch() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"data":[{"object":"embedding","embedding":[0.1,0.2],"index":0}]}
                """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embedQuery("q"))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("维度");
    }

    @Test
    void rejectsBlankTextAndMissingKeyWithoutCallingApi() {
        assertThatThrownBy(() -> client.embedDocuments(List.of("ok", " ")))
                .isInstanceOf(IllegalArgumentException.class);

        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer noCalls = MockRestServiceServer.bindTo(b).build();
        VoyageEmbeddingClient noKey = new VoyageEmbeddingClient(b, "", "voyage-4", DIM, 2, 3, ms -> {});
        assertThatThrownBy(() -> noKey.embedQuery("q"))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("VOYAGE_API_KEY");
        noCalls.verify();
    }

    // 第 i 条向量为 [i, 0.5, 1]，用第一个分量识别它对应哪条输入
    private static String response(int... firstValues) {
        List<String> items = new ArrayList<>();
        for (int idx = 0; idx < firstValues.length; idx++) {
            items.add(item(idx, firstValues[idx]));
        }
        return """
                {"object":"list","data":[%s],"model":"voyage-4","usage":{"total_tokens":1}}
                """.formatted(String.join(",", items));
    }

    private static String item(int index, int firstValue) {
        return """
                {"object":"embedding","embedding":[%d,0.5,1],"index":%d}""".formatted(firstValue, index);
    }
}
