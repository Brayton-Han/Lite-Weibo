package com.brayton.weibo.rerank;

/** 送进 reranker 的一条候选：帖子 id 与正文。 */
public record RerankCandidate(long postId, String text) {}
