package com.brayton.weibo.dto;

import com.brayton.weibo.generation.CitedAnswer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 站内问答的结果。{@link #mode} 决定前端渲染哪一块，其余字段按 mode 只填一部分：
 * <ul>
 *   <li>{@code ANSWER}：{@link #answer}，每段带引用；{@link #sourcePosts} 是被引用的帖子，顺序同 {@code answer.sources}</li>
 *   <li>{@code NO_ANSWER}：{@link #message} 为"站内没有相关讨论"，{@link #refusal} 说明是哪一层拒的</li>
 *   <li>{@code RESONANCE}：{@link #posts}，语义搜索结果，不生成</li>
 *   <li>{@code RISK}：{@link #message} 与 {@link #helplines}，既不生成也不带检索结果</li>
 *   <li>{@code DEGRADED}（Day 17）：AI 回答这次不可用，{@link #degradation} 说明原因，{@link #message} 为提示文案；
 *       {@link #posts} 是能拿到的检索结果（可能为空，比如被限流时不再调任何外部服务），前端照常展示关键词结果</li>
 * </ul>
 * {@link #cached} 为 true 表示结果来自问答缓存（{@code AskCache}），这次没有调用模型。
 */
@Data
@Builder
public class AskResponse {

    public enum Mode { ANSWER, NO_ANSWER, RESONANCE, RISK, DEGRADED }

    /**
     * 拒答发生在哪一层：检索分数不够（没调模型）、过滤后没有可用帖子、模型读完帖子后自己拒答（回答不带引用）、
     * 回答里出现 system prompt 片段被输出过滤拦下
     */
    public enum Refusal { LOW_SCORE, NO_DOCS, MODEL, FILTERED }

    /**
     * 为什么降级：用户被限流、当日 AI 花费到了阈值、Claude 调用失败 / 超时、向量检索（Voyage）不可用、问答线程池已满
     */
    public enum Degradation { RATE_LIMITED, BUDGET_EXHAUSTED, AI_UNAVAILABLE, AI_TIMEOUT, SEARCH_UNAVAILABLE, OVERLOADED }

    private Mode mode;

    private CitedAnswer answer;

    /** 被引用的帖子，前端渲染成卡片供角标跳转；生成之后被删或改为非公开的会缺席 */
    private List<PostResponse> sourcePosts;

    private List<ScoredPostResponse> posts;

    private String message;

    private Refusal refusal;

    private List<Helpline> helplines;

    private Degradation degradation;

    /** 被限流时多少秒后可以再问 */
    private Long retryAfterSeconds;

    private boolean cached;

    public record Helpline(String name, String phone, String note) {}
}
