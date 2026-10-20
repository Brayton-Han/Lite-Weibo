package com.brayton.weibo.generation;

/**
 * query 意图，决定这次搜索走哪条路（Day 13 意图路由）。前两类与语料层 {@code experience} / {@code resonance} 对应。
 */
public enum QueryIntent {
    /** 经验类：存在可被帖子回答的信息需求 → 进生成层，带引用作答。唯一进生成层的一类 */
    EXPERIENCE,
    /** 共鸣类：求同类、求共鸣，没有客观答案 → 不生成，直接返回语义搜索结果 */
    RESONANCE,
    /** 风险类：自伤/自杀倾向 → 走干预文案，既不生成也不返回检索结果（Day 14） */
    RISK
}
