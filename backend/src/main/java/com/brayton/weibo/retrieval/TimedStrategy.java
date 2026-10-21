package com.brayton.weibo.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 给检索策略计时的装饰器（RAG Day 17）：指标 {@code search.retrieval.latency}，tag {@code stage} 区分是哪一层。
 * 线上语义搜索是 rerank(rrf(lexical, vector)) 套起来的，每层各包一个，就能分别看到词面 / 向量 / 融合 / 重排的耗时；
 * 外层的耗时包含内层，rrf 的耗时约等于两路里慢的那一路。失败的调用也计时（tag {@code outcome=error}）。
 */
public class TimedStrategy implements RetrievalStrategy {

    public static final String METRIC = "search.retrieval.latency";

    private final RetrievalStrategy delegate;
    private final Timer ok;
    private final Timer error;

    public TimedStrategy(RetrievalStrategy delegate, String stage, MeterRegistry meters) {
        this.delegate = delegate;
        this.ok = timer(meters, stage, "ok");
        this.error = timer(meters, stage, "error");
    }

    private static Timer timer(MeterRegistry meters, String stage, String outcome) {
        return Timer.builder(METRIC).tags("stage", stage, "outcome", outcome).register(meters);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        long start = System.nanoTime();
        try {
            List<ScoredPost> result = delegate.retrieve(query, k, requesterId);
            ok.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException e) {
            error.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            throw e;
        }
    }
}
