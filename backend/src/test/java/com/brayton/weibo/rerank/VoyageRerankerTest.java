package com.brayton.weibo.rerank;

import com.brayton.weibo.retrieval.ScoredPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 不连网：用 MockRestServiceServer 验证报文、index 映射、同分处理与两种重试口径。 */
class VoyageRerankerTest {

    private static final String BASE = "https://voyage.test";
    private static final String URL = BASE + "/v1/rerank";

    private static final List<RerankCandidate> CANDIDATES = List.of(
            new RerankCandidate(101, "考核期加班"),
            new RerankCandidate(102, "周末去爬山"),
            new RerankCandidate(103, "转正答辩准备"));

    private MockRestServiceServer server;
    private List<Long> sleeps;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        sleeps = new ArrayList<>();
    }

    private VoyageReranker online() {
        return new VoyageReranker(builder, BASE, "test-key", "rerank-3", VoyageReranker.ONLINE_MAX_ATTEMPTS, false, sleeps::add);
    }

    private VoyageReranker offline() {
        return new VoyageReranker(builder, BASE, "test-key", "rerank-3", VoyageReranker.OFFLINE_MAX_ATTEMPTS, true, sleeps::add);
    }

    private static String response(long tokens, String... items) {
        return """
                {"object":"list","data":[%s],"model":"rerank-3","usage":{"total_tokens":%d}}
                """.formatted(String.join(",", items), tokens);
    }

    private static String item(int index, double score) {
        return "{\"index\":%d,\"relevance_score\":%s}".formatted(index, score);
    }

    @Test
    void sendsDocumentsInCandidateOrderAndMapsIndexBackToPostId() {
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("rerank-3"))
                .andExpect(jsonPath("$.query").value("转正前压力大"))
                .andExpect(jsonPath("$.documents[0]").value("考核期加班"))
                .andExpect(jsonPath("$.documents[2]").value("转正答辩准备"))
                .andExpect(jsonPath("$.top_k").value(2))
                .andExpect(jsonPath("$.return_documents").value(false))
                .andRespond(withSuccess(response(42, item(2, 0.9), item(0, 0.4)), MediaType.APPLICATION_JSON));

        VoyageReranker r = online();
        List<ScoredPost> out = r.rerank("转正前压力大", CANDIDATES, 2);

        assertThat(out).extracting(ScoredPost::postId).containsExactly(103L, 101L);
        assertThat(out.get(0).score()).isEqualTo(0.9);
        assertThat(r.totalTokens()).isEqualTo(42);
        server.verify();
    }

    @Test
    void topKIsCappedAtCandidateCount() {
        server.expect(jsonPath("$.top_k").value(3))
                .andRespond(withSuccess(response(1, item(0, 0.3), item(1, 0.2), item(2, 0.1)), MediaType.APPLICATION_JSON));

        assertThat(online().rerank("q", CANDIDATES, 10)).hasSize(3);
        server.verify();
    }

    @Test
    void tiesAndUnsortedResponseAreOrderedByScoreThenFirstStageRank() {
        // 服务端乱序且 0、2 同分：按分数降序，同分时第一阶段名次靠前（下标小）的在前
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response(1, item(1, 0.1), item(2, 0.5), item(0, 0.5)), MediaType.APPLICATION_JSON));

        assertThat(online().rerank("q", CANDIDATES, 3)).extracting(ScoredPost::postId).containsExactly(101L, 103L, 102L);
    }

    @Test
    void rejectsOutOfRangeOrDuplicateIndex() {
        server.expect(ExpectedCount.twice(), requestTo(URL))
                .andRespond(withSuccess(response(1, item(0, 0.5), item(0, 0.4)), MediaType.APPLICATION_JSON));
        VoyageReranker r = online();

        assertThatThrownBy(() -> r.rerank("q", CANDIDATES, 2)).isInstanceOf(RerankException.class).hasMessageContaining("index");
        assertThatThrownBy(() -> r.rerank("q", CANDIDATES, 3)).isInstanceOf(RerankException.class).hasMessageContaining("条数");
    }

    @Test
    void emptyCandidatesSkipTheApi() {
        assertThat(online().rerank("q", List.of(), 10)).isEmpty();
        server.verify();   // 没有任何请求
    }

    @Test
    void missingKeyFailsWithoutCallingApi() {
        VoyageReranker r = new VoyageReranker(builder, BASE, "", "rerank-3", 2, false, sleeps::add);

        assertThatThrownBy(() -> r.rerank("q", CANDIDATES, 2)).isInstanceOf(RerankException.class).hasMessageContaining("VOYAGE_API_KEY");
        server.verify();
    }

    @Test
    void blankCandidateTextIsRejected() {
        List<RerankCandidate> withBlank = List.of(new RerankCandidate(1, "a"), new RerankCandidate(2, " "));

        assertThatThrownBy(() -> online().rerank("q", withBlank, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlineDoesNotRetry429() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> online().rerank("q", CANDIDATES, 2)).isInstanceOf(RerankException.class).hasMessageContaining("429");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void unreadableResponseBodyBecomesRerankExceptionSoCallersCanFallBack() {
        // 状态 200 但响应体读不出来（线上是读超时打在 body 上，这里用坏 JSON 走同一条 RestClientException 路径）
        server.expect(ExpectedCount.twice(), requestTo(URL))
                .andRespond(withSuccess("{\"data\": [", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> online().rerank("q", CANDIDATES, 2))
                .isInstanceOf(RerankException.class).hasMessageContaining("读取响应失败");
        server.verify();
    }

    @Test
    void onlineRetries5xxOnceThenGivesUp() {
        server.expect(ExpectedCount.twice(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> online().rerank("q", CANDIDATES, 2)).isInstanceOf(RerankException.class).hasMessageContaining("502");
        assertThat(sleeps).hasSize(1).allSatisfy(ms -> assertThat(ms).isLessThan(1_000L));
        server.verify();
    }

    @Test
    void offlineBacksOffOn429ThenSucceeds() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response(7, item(1, 0.8), item(0, 0.2)), MediaType.APPLICATION_JSON));

        VoyageReranker r = offline();
        assertThat(r.rerank("q", CANDIDATES, 2)).extracting(ScoredPost::postId).containsExactly(102L, 101L);
        // 分钟窗口限流：至少等 20s
        assertThat(sleeps).hasSize(1).allSatisfy(ms -> assertThat(ms).isGreaterThanOrEqualTo(20_000L));
        assertThat(r.totalTokens()).isEqualTo(7);
        server.verify();
    }

    @Test
    void offlineHonorsRetryAfter() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "3");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response(1, item(0, 0.5)), MediaType.APPLICATION_JSON));

        offline().rerank("q", CANDIDATES, 1);
        assertThat(sleeps).containsExactly(3_000L);
    }

    @Test
    void modelIdIsPrefixed() {
        assertThat(online().modelId()).isEqualTo("voyage/rerank-3");
    }
}
