package com.brayton.weibo.service;

import com.brayton.weibo.dto.AskResponse;
import com.brayton.weibo.dto.AskResponse.Degradation;
import com.brayton.weibo.dto.AskResponse.Helpline;
import com.brayton.weibo.dto.AskResponse.Mode;
import com.brayton.weibo.dto.AskResponse.Refusal;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.ScoredPostResponse;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.generation.AnswerGenerator;
import com.brayton.weibo.generation.CitedAnswer;
import com.brayton.weibo.generation.ClaudeCallException;
import com.brayton.weibo.generation.IntentClassifier;
import com.brayton.weibo.generation.PromptLeakGuard;
import com.brayton.weibo.generation.QueryIntent;
import com.brayton.weibo.generation.RiskKeywordGuard;
import com.brayton.weibo.generation.SourceDoc;
import com.brayton.weibo.generation.SourceDocLoader;
import com.brayton.weibo.ratelimit.RateLimitPolicies;
import com.brayton.weibo.ratelimit.RateLimiter;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.retrieval.TimedStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 站内问答（RAG Day 14）：先分意图，再决定走哪条路，三种情况分别闭嘴。
 *
 * <pre>
 * RISK       → 干预文案。不检索、不生成，所以不受检索分数影响：分数再高也不返回帖子
 * RESONANCE  → 语义搜索结果列表，不生成（对"撑不下去了想裸辞"硬编建议是产品缺陷）
 * EXPERIENCE → 向量检索 → top-1 低于阈值直接拒答、不调模型 → 生成带引用的回答
 * </pre>
 *
 * <h3>阈值为什么是向量 top-1 余弦</h3>
 * Day 14 在 train 集上比过（EVAL.md「Day 14 · 拒答阈值」）：RRF 分数只由名次决定，可答与无答案的 top-1 都是 0.909，没有信号；
 * top-1 与 top-10 / 均值的分差这类相对判据，无答案 query 并不比可答的小。只有向量 top-1 有区分度。
 * 阈值是针对 {@code voyage-4} 校准的，换 embedding 模型或检索方案必须重新校准——所以 Day 17 接的 rerank
 * 只用于共鸣类的"相关帖子"（{@link SemanticSearchService}），经验类仍走纯向量。
 *
 * <h3>为什么偏向少拒</h3>
 * 阈值只是第一道、省钱的一道：漏过去的无答案 query 还有生成层规则 3（"站内没有找到相关讨论"）兜着，代价是一次调用；
 * 误拒则直接让用户拿不到答案。所以阈值取在误拒尽量少的位置，而不是把 train 上的无答案全挡住。
 * 模型拒答的标志是回答不带任何引用——没有引用的回答也不该展示，一并按拒答处理。
 *
 * <h3>注入</h3>
 * 帖子是不可信输入，三道防线：document 块隔离 + system 规则 4（{@code AnswerPromptBuilder}），
 * 以及生成后的 {@link PromptLeakGuard}——回答里出现 system prompt 片段就拦下、记日志，按拒答返回。
 *
 * <h3>流式</h3>
 * {@link #askStreaming} 与 {@link #ask} 是同一条路径，只是分类完成后先通知意图、生成时逐段推文本。
 * 引用、"无引用即拒答"都只能在生成结束后判断，所以最终结果仍以 {@link AskResponse} 为准，已推出的文本可能被它撤回。
 * 输出过滤不能等到最后：每段文本推出去之前先对累计文本跑 {@link PromptLeakGuard}，命中就中止生成，
 * 保证推给用户的文本里从来没出现过完整的泄露片段。
 *
 * <h3>Day 17：限流 → 缓存 → 熔断 → 分类</h3>
 * <ol>
 *   <li>限流（{@link RateLimiter}，默认每人 20 次 / 小时）放在最前面，按请求计而不是按花费计：缓存命中也消耗令牌</li>
 *   <li>缓存（{@link AskCache}）：命中直接返回，不调模型；熔断之后缓存里的结果照常可用</li>
 *   <li>成本熔断（{@link AiBudgetService}）：当日花费到阈值后不再调 Claude</li>
 * </ol>
 *
 * <h3>降级：Claude 挂了，搜索还能用</h3>
 * 这个功能的核心价值是检索，AI 回答是增强不是依赖。被限流、熔断、Claude 调用失败或超时、Voyage 不可用，
 * 都返回 {@code DEGRADED}：提示"AI 回答暂不可用"，带上能拿到的检索结果，不报错、不返回 500。
 * <p>
 * 难点在 Day 14 的硬要求：风险类 query 不能看到帖子。意图未知时（分类前就降级、或分类本身失败）先过本地风险词表
 * {@link RiskKeywordGuard}，命中按风险类处理，没命中才给检索结果。词表召回比分类器低，这是"降级时搜索可用"的代价，
 * 量化结果见 EVAL.md「Day 17 · 风险词表」。分类器可用时词表不参与路由。
 * <p>
 * 评测（tools profile）把 {@code ask.degrade.enabled} 关掉：静默降级会让指标混进检索结果，必须失败得明明白白，与 rerank 的 fallbackOnError 同一口径。
 */
@Slf4j
@Service
public class AskService {

    static final String NO_ANSWER_MESSAGE = "站内没有找到相关讨论";

    static final String DEGRADED_MESSAGE = "AI 回答暂不可用，先看看相关帖子";

    static final String RISK_MESSAGE = """
            听起来你现在真的很难受。你不需要一个人扛着——现在就可以打给下面的热线，和专业的人聊一聊。
            如果你已经有伤害自己的打算或正处在危险中，请立刻拨打 110 或 120，或者告诉身边你信任的人。""";

    /**
     * 真实求助渠道，2026-10-20 核实：12356 是国家卫健委设立的全国统一心理援助热线，2025-05-01 起 31 个省份全部开通（新华社）；
     * 希望 24 热线 2013 年起用 400-161-9995；北京那条手机拨 010-82951332。改动前逐条重新核实
     */
    static final List<Helpline> HELPLINES = List.of(
            new Helpline("全国心理援助热线", "12356", "国家卫健委统一热线"),
            new Helpline("希望 24 热线", "400-161-9995", "24 小时"),
            new Helpline("北京心理危机研究与干预中心", "010-82951332", "24 小时"),
            new Helpline("紧急情况", "110 / 120", "有立即危险时"));

    private final IntentClassifier classifier;
    private final AnswerGenerator generator;
    private final SourceDocLoader docLoader;
    private final PostService postService;
    private final SemanticSearchService semanticSearch;
    private final EmbeddingClient embeddingClient;
    private final PostEmbeddingRepository embeddingRepository;
    private final RateLimiter rateLimiter;
    private final RateLimitPolicies policies;
    private final AskCache cache;
    private final AiBudgetService budget;
    private final MeterRegistry meters;
    private final double minTop1Score;
    private final int topK;
    private final boolean degradeEnabled;
    private final Timer vectorTimer;

    public AskService(
            IntentClassifier classifier, AnswerGenerator generator, SourceDocLoader docLoader, PostService postService,
            SemanticSearchService semanticSearch, EmbeddingClient embeddingClient, PostEmbeddingRepository embeddingRepository,
            RateLimiter rateLimiter, RateLimitPolicies policies, AskCache cache, AiBudgetService budget, MeterRegistry meters,
            @Value("${ask.refusal.min-top1-score:0.40}") double minTop1Score,
            @Value("${ask.top-k:10}") int topK,
            @Value("${ask.degrade.enabled:true}") boolean degradeEnabled
    ) {
        this.classifier = classifier;
        this.generator = generator;
        this.docLoader = docLoader;
        this.postService = postService;
        this.semanticSearch = semanticSearch;
        this.embeddingClient = embeddingClient;
        this.embeddingRepository = embeddingRepository;
        this.rateLimiter = rateLimiter;
        this.policies = policies;
        this.cache = cache;
        this.budget = budget;
        this.meters = meters;
        this.minTop1Score = minTop1Score;
        this.topK = topK;
        this.degradeEnabled = degradeEnabled;
        // 与语义搜索的检索链同一个指标，stage 区分：问答经验类的纯向量检索（含 query 向量化）
        this.vectorTimer = Timer.builder(TimedStrategy.METRIC).tags("stage", "ask-vector", "outcome", "ok").register(meters);
    }

    public AskResponse ask(String query, Long selfId) {
        return trace(query, selfId).response();
    }

    /** 流式问答的进度回调，在调用 {@link #askStreaming} 的线程上触发 */
    public interface AskListener {
        /** 意图分类完成（或缓存命中、或降级时本地词表判为风险类），检索和生成之前 */
        void onIntent(QueryIntent intent);

        /** 生成的一段文本增量，已过输出过滤 */
        void onDelta(String text);
    }

    private static final AskListener SILENT = new AskListener() {
        @Override
        public void onIntent(QueryIntent intent) {}

        @Override
        public void onDelta(String text) {}
    };

    public AskResponse askStreaming(String query, Long selfId, AskListener listener) {
        return run(query, selfId, Objects.requireNonNull(listener), true).response();
    }

    /**
     * 与 {@link #ask} 同一条路径，额外带出中间结果给评测用：生成层评测的 judge 要看到送进模型的帖子，
     * 意图与拒答指标要看到分类结果和 top-1。线上只用 {@link #ask}。
     *
     * @param intent 降级且意图未知时为 null
     * @param top1 向量 top-1 余弦；没走检索（RISK / RESONANCE / 缓存命中）时为 NaN
     * @param docs 送进生成层的帖子；没调生成时为空
     */
    public record Trace(QueryIntent intent, double top1, List<SourceDoc> docs, AskResponse response) {}

    public Trace trace(String query, Long selfId) {
        return run(query, selfId, SILENT, false);
    }

    /**
     * 流式问答的线程池满了：不调任何外部服务，在请求线程上直接给降级结果（风险词表照常生效）。
     * 不扣限流令牌——这次请求本来就没被服务
     */
    public AskResponse overloaded(String query, Long selfId, AskListener listener) {
        String q = validate(query);
        return record(degradeUnknownIntent(q, selfId, listener, Degradation.OVERLOADED, null, false), "live").response();
    }

    /** @param streaming false 时走非流式生成 */
    private Trace run(String query, Long selfId, AskListener listener, boolean streaming) {
        Timer.Sample sample = Timer.start(meters);
        String q = validate(query);
        Trace trace;
        String source = "live";

        RateLimiter.Decision quota = rateLimiter.tryAcquire(policies.ask(), selfId);
        Optional<AskCache.Entry> cached = quota.allowed() ? cache.get(q) : Optional.empty();
        if (!quota.allowed()) {
            // 被限流的用户不再触发任何外部调用，连 embedding 都不调：检索结果交给前端的关键词搜索
            trace = degradeUnknownIntent(q, selfId, listener, Degradation.RATE_LIMITED, quota.retryAfterSeconds(), false);
        } else if (cached.isPresent()) {
            source = "cache";
            listener.onIntent(cached.get().intent());
            trace = fromCache(cached.get(), selfId);
        } else if (budget.exhausted()) {
            trace = degradeUnknownIntent(q, selfId, listener, Degradation.BUDGET_EXHAUSTED, null, true);
        } else {
            trace = classifyAndRoute(q, selfId, listener, streaming);
            store(q, trace);
        }

        record(trace, source);
        sample.stop(Timer.builder("ask.latency").tags("mode", trace.response().getMode().name(), "source", source)
                .register(meters));
        return trace;
    }

    private static String validate(String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > PostService.SEMANTIC_QUERY_MAX_LENGTH) {
            throw new WeiboException(CommonErrorCode.SEARCH_QUERY_INVALID);
        }
        return q;
    }

    private Trace classifyAndRoute(String q, Long selfId, AskListener listener, boolean streaming) {
        QueryIntent intent;
        try {
            intent = classifier.classify(q);
        } catch (ClaudeCallException e) {
            log.warn("意图分类失败 kind={}，不猜类别：{}", e.kind(), e.getMessage());
            if (!degradeEnabled) {
                throw new WeiboException(CommonErrorCode.ASK_UNAVAILABLE);
            }
            return degradeUnknownIntent(q, selfId, listener, degradation(e), null, true);
        }
        listener.onIntent(intent);

        return switch (intent) {
            case RISK -> risk();
            case RESONANCE -> resonance(q, selfId);
            case EXPERIENCE -> answer(q, selfId, listener, streaming);
        };
    }

    private static Trace risk() {
        return new Trace(QueryIntent.RISK, Double.NaN, List.of(),
                AskResponse.builder().mode(Mode.RISK).message(RISK_MESSAGE).helplines(HELPLINES).build());
    }

    private Trace resonance(String q, Long selfId) {
        List<ScoredPostResponse> posts;
        try {
            posts = semanticSearch.search(q, topK, selfId);
        } catch (WeiboException e) {
            if (!degradeEnabled || e.getCode() != CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE.getCode()) {
                throw e;
            }
            return degraded(QueryIntent.RESONANCE, Double.NaN, List.of(), Degradation.SEARCH_UNAVAILABLE, List.of(), null);
        }
        return new Trace(QueryIntent.RESONANCE, Double.NaN, List.of(),
                AskResponse.builder().mode(Mode.RESONANCE).posts(posts).build());
    }

    private Trace answer(String q, Long selfId, AskListener listener, boolean streaming) {
        List<ScoredPost> hits;
        try {
            hits = retrieve(q);
        } catch (WeiboException e) {
            if (!degradeEnabled) {
                throw e;
            }
            return degraded(QueryIntent.EXPERIENCE, Double.NaN, List.of(), Degradation.SEARCH_UNAVAILABLE, List.of(), null);
        }
        double top1 = hits.isEmpty() ? Double.NEGATIVE_INFINITY : hits.get(0).score();
        if (top1 < minTop1Score) {
            log.info("拒答 reason=LOW_SCORE top1={} 阈值={}", top1, minTop1Score);
            return new Trace(QueryIntent.EXPERIENCE, top1, List.of(), noAnswer(Refusal.LOW_SCORE));
        }

        List<SourceDoc> docs = docLoader.load(hits);
        if (docs.isEmpty()) {
            return new Trace(QueryIntent.EXPERIENCE, top1, docs, noAnswer(Refusal.NO_DOCS));
        }

        AnswerGenerator.GeneratedAnswer generated;
        try {
            generated = streaming ? generateStreaming(q, docs, listener) : generator.generate(q, docs);
        } catch (LeakAbort e) {
            log.warn("输出过滤：流式回答含 system prompt 片段，已中止生成 posts={} 已生成={}",
                    docs.stream().map(SourceDoc::postId).toList(), e.streamed);
            return new Trace(QueryIntent.EXPERIENCE, top1, docs, noAnswer(Refusal.FILTERED));
        } catch (ClaudeCallException e) {
            log.warn("生成失败 kind={}：{}", e.kind(), e.getMessage());
            if (!degradeEnabled) {
                throw new WeiboException(CommonErrorCode.ASK_UNAVAILABLE);
            }
            // 检索已经做完了，把送进模型的那些帖子直接给用户
            return degraded(QueryIntent.EXPERIENCE, top1, docs, degradation(e),
                    postService.toScoredResponses(hits, selfId), null);
        }
        CitedAnswer answer = generated.answer();
        if (PromptLeakGuard.leaks(answer.text())) {
            // 记下 message id 与命中的帖子，事后能查是哪条帖子把模型带偏的
            log.warn("输出过滤：回答含 system prompt 片段，已拦截 id={} posts={} 回答={}",
                    generated.raw() == null ? "-" : generated.raw().id(),
                    docs.stream().map(SourceDoc::postId).toList(), answer.text());
            return new Trace(QueryIntent.EXPERIENCE, top1, docs, noAnswer(Refusal.FILTERED));
        }
        if (!answer.hasCitations()) {
            log.info("拒答 reason=MODEL top1={} 回答={}", top1, answer.text());
            return new Trace(QueryIntent.EXPERIENCE, top1, docs, noAnswer(Refusal.MODEL));
        }
        return new Trace(QueryIntent.EXPERIENCE, top1, docs, answered(answer, selfId));
    }

    private AskResponse answered(CitedAnswer answer, Long selfId) {
        List<PostResponse> sourcePosts = postService.getPublicPosts(
                answer.sources().stream().map(CitedAnswer.Source::postId).toList(), selfId);
        return AskResponse.builder().mode(Mode.ANSWER).answer(answer).sourcePosts(sourcePosts).build();
    }

    /**
     * 意图未知时的降级：先过本地风险词表，命中按风险类处理；否则给检索结果。
     * @param withPosts false 时不调 embedding（被限流 / 线程池满），posts 为空，前端用关键词结果
     */
    private Trace degradeUnknownIntent(String q, Long selfId, AskListener listener, Degradation reason,
                                       Long retryAfterSeconds, boolean withPosts) {
        if (RiskKeywordGuard.flags(q)) {
            log.info("降级 reason={}，本地风险词表命中，按风险类处理", reason);
            Counter.builder("ask.risk.keyword.hits").register(meters).increment();
            listener.onIntent(QueryIntent.RISK);
            return risk();
        }
        List<ScoredPostResponse> posts = List.of();
        if (withPosts) {
            try {
                posts = semanticSearch.search(q, topK, selfId);
            } catch (WeiboException e) {
                log.warn("降级时语义搜索也不可用，只返回提示：{}", e.getMessage());
            }
        }
        log.info("降级 reason={} posts={}", reason, posts.size());
        return degraded(null, Double.NaN, List.of(), reason, posts, retryAfterSeconds);
    }

    private static Trace degraded(QueryIntent intent, double top1, List<SourceDoc> docs, Degradation reason,
                                  List<ScoredPostResponse> posts, Long retryAfterSeconds) {
        return new Trace(intent, top1, docs, AskResponse.builder()
                .mode(Mode.DEGRADED).degradation(reason).message(DEGRADED_MESSAGE)
                .posts(posts).retryAfterSeconds(retryAfterSeconds).build());
    }

    private static Degradation degradation(ClaudeCallException e) {
        return e.kind() == ClaudeCallException.Kind.TIMEOUT ? Degradation.AI_TIMEOUT : Degradation.AI_UNAVAILABLE;
    }

    /** 降级、FILTERED、NO_DOCS 不进缓存，理由见 {@link AskCache} */
    private void store(String q, Trace trace) {
        AskResponse r = trace.response();
        boolean cacheable = switch (r.getMode()) {
            case ANSWER, RESONANCE, RISK -> true;
            case NO_ANSWER -> r.getRefusal() == Refusal.LOW_SCORE || r.getRefusal() == Refusal.MODEL;
            case DEGRADED -> false;
        };
        if (!cacheable) {
            return;
        }
        List<ScoredPost> hits = r.getMode() == Mode.RESONANCE
                ? r.getPosts().stream().map(p -> new ScoredPost(p.getPost().getId(), p.getScore())).toList()
                : null;
        cache.put(q, new AskCache.Entry(trace.intent(), r.getMode(), r.getRefusal(), r.getAnswer(), hits));
    }

    private Trace fromCache(AskCache.Entry e, Long selfId) {
        AskResponse response = switch (e.mode()) {
            case RISK -> risk().response();
            case RESONANCE -> AskResponse.builder().mode(Mode.RESONANCE)
                    .posts(postService.toScoredResponses(e.hits(), selfId)).build();
            case ANSWER -> answered(e.answer(), selfId);
            case NO_ANSWER -> noAnswer(e.refusal());
            case DEGRADED -> throw new IllegalStateException("降级结果不该进缓存");
        };
        response.setCached(true);
        return new Trace(e.intent(), Double.NaN, List.of(), response);
    }

    /** 指标 {@code ask.outcomes}：拒答率 = NO_ANSWER / (ANSWER + NO_ANSWER)，detail 是拒答层或降级原因 */
    private Trace record(Trace trace, String source) {
        AskResponse r = trace.response();
        String detail = r.getRefusal() != null ? r.getRefusal().name()
                : r.getDegradation() != null ? r.getDegradation().name() : "-";
        Counter.builder("ask.outcomes").tags("mode", r.getMode().name(), "detail", detail, "source", source)
                .register(meters).increment();
        return trace;
    }

    /** 每段文本推出去之前先对累计文本做输出过滤，命中即抛 {@link LeakAbort} 中止流 */
    private AnswerGenerator.GeneratedAnswer generateStreaming(String q, List<SourceDoc> docs, AskListener listener) {
        StringBuilder streamed = new StringBuilder();
        return generator.generateStreaming(q, docs, text -> {
            streamed.append(text);
            if (PromptLeakGuard.leaks(streamed.toString())) {
                throw new LeakAbort(streamed.toString());
            }
            listener.onDelta(text);
        });
    }

    private static final class LeakAbort extends RuntimeException {
        final String streamed;

        LeakAbort(String streamed) {
            super(null, null, false, false);
            this.streamed = streamed;
        }
    }

    /** 与 Day 14 阈值校准同一条检索：voyage-4 向量、PUBLIC 帖子、按余弦从高到低 */
    private List<ScoredPost> retrieve(String q) {
        long start = System.nanoTime();
        float[] vector;
        try {
            vector = embeddingClient.embedQuery(q);
        } catch (EmbeddingException e) {
            log.warn("问答查询向量化失败：{}", e.getMessage());
            throw new WeiboException(CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
        }
        List<ScoredPost> hits = embeddingRepository.searchNearest(vector, embeddingClient.modelId(), topK).stream()
                .map(h -> new ScoredPost(h.postId(), h.score()))
                .toList();
        vectorTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return hits;
    }

    private static AskResponse noAnswer(Refusal refusal) {
        return AskResponse.builder().mode(Mode.NO_ANSWER).message(NO_ANSWER_MESSAGE).refusal(refusal).build();
    }
}
