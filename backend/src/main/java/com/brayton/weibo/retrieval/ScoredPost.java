package com.brayton.weibo.retrieval;

/**
 * 检索结果的一条：帖子 id 与该策略自己的分数。
 * 不同策略的分数量纲不同（相似度、ts_rank、余弦），彼此不可比——跨策略只比较排名（Day 11 的 RRF 也只用排名）。
 */
public record ScoredPost(long postId, double score) {}
