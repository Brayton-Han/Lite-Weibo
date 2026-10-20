package com.brayton.weibo.dto;

import com.brayton.weibo.generation.CitedAnswer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 站内问答的结果。{@link #mode} 决定前端渲染哪一块，其余字段按 mode 只填一部分：
 * <ul>
 *   <li>{@code ANSWER}：{@link #answer}，每段带引用</li>
 *   <li>{@code NO_ANSWER}：{@link #message} 为"站内没有相关讨论"，{@link #refusal} 说明是哪一层拒的</li>
 *   <li>{@code RESONANCE}：{@link #posts}，语义搜索结果，不生成</li>
 *   <li>{@code RISK}：{@link #message} 与 {@link #helplines}，既不生成也不带检索结果</li>
 * </ul>
 */
@Data
@Builder
public class AskResponse {

    public enum Mode { ANSWER, NO_ANSWER, RESONANCE, RISK }

    /**
     * 拒答发生在哪一层：检索分数不够（没调模型）、过滤后没有可用帖子、模型读完帖子后自己拒答（回答不带引用）、
     * 回答里出现 system prompt 片段被输出过滤拦下
     */
    public enum Refusal { LOW_SCORE, NO_DOCS, MODEL, FILTERED }

    private Mode mode;

    private CitedAnswer answer;

    private List<ScoredPostResponse> posts;

    private String message;

    private Refusal refusal;

    private List<Helpline> helplines;

    public record Helpline(String name, String phone, String note) {}
}
