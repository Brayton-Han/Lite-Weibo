package com.brayton.weibo.generation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 输出侧过滤：生成的回答里出现 system prompt 的片段就拦下（RAG Day 14）。
 *
 * <p>注入防护的最后一道。前面两道（document 隔离 + system 规则 4）挡住的是"模型照做"，这一道兜的是"模型还是照做了"——
 * 只要输出里出现了规则原文，不管模型是被哪条帖子骗的，都不交给用户。
 *
 * <h3>怎么判</h3>
 * 把 system prompt 去掉空白后切成所有长度为 {@link #WINDOW} 的连续片段，回答（同样去掉空白）里出现任何一个就算泄露。
 * 12 个字足够长，正常回答不会碰巧和规则原文连续 12 个字相同；又足够短，模型只复述半句规则也能抓到。
 * {@link #ALLOWED} 里的句子是规则要求模型原样说的（规则 3 的拒答句），从 prompt 里挖掉再切，不算泄露。
 *
 * <p>只看模型写的 {@link CitedAnswer#text()}，不看 {@code cited_text}：后者是 API 从帖子里逐字截的原文，
 * 就算有人把规则抄进帖子里，那也是帖子内容，不是 system prompt 泄露。
 */
public final class PromptLeakGuard {

    static final int WINDOW = 12;

    static final List<String> ALLOWED = List.of("站内没有找到相关讨论");

    private static final Set<String> WINDOWS = windows(AnswerPromptBuilder.SYSTEM_PROMPT);

    private PromptLeakGuard() {}

    public static boolean leaks(String answer) {
        String a = normalize(answer);
        for (int i = 0; i + WINDOW <= a.length(); i++) {
            if (WINDOWS.contains(a.substring(i, i + WINDOW))) {
                return true;
            }
        }
        return false;
    }

    static Set<String> windows(String prompt) {
        String p = normalize(prompt);
        // 挖掉允许的句子后按断点分段，片段不跨过断点
        for (String allowed : ALLOWED) {
            p = p.replace(normalize(allowed), "\0");
        }
        Set<String> out = new HashSet<>();
        for (String part : p.split("\0")) {
            for (int i = 0; i + WINDOW <= part.length(); i++) {
                out.add(part.substring(i, i + WINDOW));
            }
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
