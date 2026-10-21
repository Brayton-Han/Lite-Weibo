package com.brayton.weibo.service;

import com.brayton.weibo.dto.AskResponse;
import com.brayton.weibo.dto.AskResponse.Helpline;
import com.brayton.weibo.dto.AskResponse.Mode;
import com.brayton.weibo.dto.AskResponse.Refusal;
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
import com.brayton.weibo.generation.SourceDoc;
import com.brayton.weibo.generation.SourceDocLoader;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.retrieval.ScoredPost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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
 * 阈值是针对 {@code voyage-4} 校准的，换 embedding 模型或检索方案（如 Day 17 接 rerank）必须重新校准。
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
 * <h3>失败</h3>
 * 意图分类失败时不猜类别、不降级成搜索结果（风险类 query 可能因此看到一堆同样绝望的帖子），直接报不可用。
 * 生成失败同样报不可用；"Claude 挂了退回纯检索"留给 Day 17 的降级设计。
 */
@Slf4j
@Service
public class AskService {

    static final String NO_ANSWER_MESSAGE = "站内没有找到相关讨论";

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
    private final EmbeddingClient embeddingClient;
    private final PostEmbeddingRepository embeddingRepository;
    private final double minTop1Score;
    private final int topK;

    public AskService(
            IntentClassifier classifier, AnswerGenerator generator, SourceDocLoader docLoader, PostService postService,
            EmbeddingClient embeddingClient, PostEmbeddingRepository embeddingRepository,
            @Value("${ask.refusal.min-top1-score:0.40}") double minTop1Score,
            @Value("${ask.top-k:10}") int topK
    ) {
        this.classifier = classifier;
        this.generator = generator;
        this.docLoader = docLoader;
        this.postService = postService;
        this.embeddingClient = embeddingClient;
        this.embeddingRepository = embeddingRepository;
        this.minTop1Score = minTop1Score;
        this.topK = topK;
    }

    public AskResponse ask(String query, Long selfId) {
        return trace(query, selfId).response();
    }

    /**
     * 与 {@link #ask} 同一条路径，额外带出中间结果给评测用：生成层评测的 judge 要看到送进模型的帖子，
     * 意图与拒答指标要看到分类结果和 top-1。线上只用 {@link #ask}。
     *
     * @param top1 向量 top-1 余弦；没走检索（RISK / RESONANCE）时为 NaN
     * @param docs 送进生成层的帖子；没调生成时为空
     */
    public record Trace(QueryIntent intent, double top1, List<SourceDoc> docs, AskResponse response) {}

    public Trace trace(String query, Long selfId) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > PostService.SEMANTIC_QUERY_MAX_LENGTH) {
            throw new WeiboException(CommonErrorCode.SEARCH_QUERY_INVALID);
        }

        QueryIntent intent;
        try {
            intent = classifier.classify(q);
        } catch (ClaudeCallException e) {
            log.warn("意图分类失败 kind={}，不猜类别：{}", e.kind(), e.getMessage());
            throw new WeiboException(CommonErrorCode.ASK_UNAVAILABLE);
        }

        return switch (intent) {
            case RISK -> new Trace(intent, Double.NaN, List.of(),
                    AskResponse.builder().mode(Mode.RISK).message(RISK_MESSAGE).helplines(HELPLINES).build());
            case RESONANCE -> new Trace(intent, Double.NaN, List.of(), AskResponse.builder().mode(Mode.RESONANCE)
                    .posts(postService.getSemanticSearchResults(q, topK, selfId)).build());
            case EXPERIENCE -> answer(q);
        };
    }

    private Trace answer(String q) {
        List<ScoredPost> hits = retrieve(q);
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
            generated = generator.generate(q, docs);
        } catch (ClaudeCallException e) {
            log.warn("生成失败 kind={}：{}", e.kind(), e.getMessage());
            throw new WeiboException(CommonErrorCode.ASK_UNAVAILABLE);
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
        return new Trace(QueryIntent.EXPERIENCE, top1, docs, AskResponse.builder().mode(Mode.ANSWER).answer(answer).build());
    }

    /** 与 Day 14 阈值校准同一条检索：voyage-4 向量、PUBLIC 帖子、按余弦从高到低 */
    private List<ScoredPost> retrieve(String q) {
        float[] vector;
        try {
            vector = embeddingClient.embedQuery(q);
        } catch (EmbeddingException e) {
            log.warn("问答查询向量化失败：{}", e.getMessage());
            throw new WeiboException(CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
        }
        return embeddingRepository.searchNearest(vector, embeddingClient.modelId(), topK).stream()
                .map(h -> new ScoredPost(h.postId(), h.score()))
                .toList();
    }

    private static AskResponse noAnswer(Refusal refusal) {
        return AskResponse.builder().mode(Mode.NO_ANSWER).message(NO_ANSWER_MESSAGE).refusal(refusal).build();
    }
}
