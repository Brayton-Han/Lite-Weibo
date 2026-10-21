package com.brayton.weibo.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索评测指标（单 query 粒度），口径见 docs/EVAL.md §3。
 * <p>
 * 约定：
 * <ul>
 *   <li>{@code ranked} 是系统返回的排序列表，下标 0 为第 1 名；</li>
 *   <li>同一文档在 {@code ranked} 中重复出现时只按首次出现计分，避免重复命中把 Recall / nDCG 抬虚；</li>
 *   <li>分级相关性 {@code grades} 中 grade &lt;= 0 视为不相关；</li>
 *   <li>无答案 query（相关集合为空）的 Recall / nDCG 没有定义，直接抛异常，由调用方单独统计拒答。</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** Recall@k = |G ∩ R[1..k]| / |G| */
    public static <T> double recallAtK(List<T> ranked, Set<T> relevant, int k) {
        requirePositiveK(k);
        if (relevant.isEmpty()) {
            throw new IllegalArgumentException("Recall is undefined for a query with no relevant documents");
        }
        return (double) hitsAtK(ranked, relevant, k) / relevant.size();
    }

    /** Precision@k = |G ∩ R[1..k]| / k（返回不足 k 条时分母仍为 k） */
    public static <T> double precisionAtK(List<T> ranked, Set<T> relevant, int k) {
        requirePositiveK(k);
        return (double) hitsAtK(ranked, relevant, k) / k;
    }

    /** 单个 query 的倒数排名 1 / rank(第一个相关结果)；全未命中为 0 */
    public static <T> double reciprocalRank(List<T> ranked, Set<T> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** MRR = 各 query 倒数排名的均值 */
    public static double meanReciprocalRank(Collection<Double> reciprocalRanks) {
        return mean(reciprocalRanks);
    }

    /**
     * nDCG@k = DCG@k / IDCG@k，DCG@k = Σ rel_i / log2(i + 1)，i 从 1 到 k。
     * IDCG 取 grades 按降序排列后的前 k 个。
     */
    public static <T> double ndcgAtK(List<T> ranked, Map<T, Integer> grades, int k) {
        requirePositiveK(k);
        double idcg = discountedGain(grades.values().stream()
                .filter(g -> g > 0)
                .sorted((a, b) -> Integer.compare(b, a))
                .limit(k)
                .toList());
        if (idcg == 0.0) {
            throw new IllegalArgumentException("nDCG is undefined for a query with no relevant documents");
        }

        Set<T> seen = new HashSet<>();
        double dcg = 0.0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            T doc = ranked.get(i);
            if (!seen.add(doc)) {
                continue;
            }
            int grade = Math.max(0, grades.getOrDefault(doc, 0));
            dcg += grade / log2(i + 2);
        }
        return dcg / idcg;
    }

    /** 二值相关性版本的 nDCG@k，相关文档 grade 均为 1 */
    public static <T> double ndcgAtK(List<T> ranked, Set<T> relevant, int k) {
        return ndcgAtK(ranked, relevant.stream().collect(Collectors.toMap(d -> d, d -> 1)), k);
    }

    /** 把分级标注收敛成二值相关集合（grade &gt; 0），供 Recall / Precision / MRR 使用 */
    public static <T> Set<T> relevantSet(Map<T, Integer> grades) {
        return grades.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** 多个 query 的宏平均 */
    public static double mean(Collection<Double> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot average an empty collection");
        }
        return values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    private static <T> int hitsAtK(List<T> ranked, Set<T> relevant, int k) {
        Set<T> hits = new HashSet<>();
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            T doc = ranked.get(i);
            if (relevant.contains(doc)) {
                hits.add(doc);
            }
        }
        return hits.size();
    }

    /** 按位置 1..n 计算 Σ gain_i / log2(i + 1) */
    private static double discountedGain(List<Integer> gains) {
        double sum = 0.0;
        for (int i = 0; i < gains.size(); i++) {
            sum += gains.get(i) / log2(i + 2);
        }
        return sum;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static void requirePositiveK(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got " + k);
        }
    }
}
