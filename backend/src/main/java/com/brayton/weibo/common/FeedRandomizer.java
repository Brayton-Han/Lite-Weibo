package com.brayton.weibo.common;

import java.util.*;


/* 从一堆 Post 里按作者尽量均匀地选出 N 条 */
public class FeedRandomizer {

    // 作者至少能拿到的 guaranteed 条数
    private final int maxGuaranteedPerAuthor;

    // 超额后的基础概率（如 0.3 = 30%）
    private final double extraProbability;

    // 每多一条降低的衰减系数（如 0.5 = 每多一条概率减半）
    private final double decayFactor;

    public FeedRandomizer(int maxGuaranteedPerAuthor, double extraProbability, double decayFactor) {
        this.maxGuaranteedPerAuthor = maxGuaranteedPerAuthor;
        this.extraProbability = extraProbability;
        this.decayFactor = decayFactor;
    }

    /**
     * @param posts 原始候选列表（随机抽样 + DB 查出来的）
     * @param targetCount 最终需要的数量
     */
    public <T> List<T> select(List<T> posts, longAuthorIdExtractor<T> extractor, int targetCount) {
        Map<Long, Integer> authorCount = new HashMap<>();
        List<T> result = new ArrayList<>();

        for (T post : posts) {
            long authorId = extractor.getAuthorId(post);
            int count = authorCount.getOrDefault(authorId, 0);

            boolean accept = false;

            if (count < maxGuaranteedPerAuthor) {
                accept = true;
            } else {
                double prob = extraProbability * Math.pow(decayFactor, count - maxGuaranteedPerAuthor);
                if (Math.random() < prob) {
                    accept = true;
                }
            }

            if (accept) {
                result.add(post);
                authorCount.put(authorId, count + 1);

                if (result.size() >= targetCount) break;
            }
        }

        // 最终洗一遍牌，让顺序更自然
        Collections.shuffle(result);
        return result;
    }

    // 为了让工具类通用，不耦合你的 Post entity
    @FunctionalInterface
    public interface longAuthorIdExtractor<T> {
        long getAuthorId(T t);
    }
}