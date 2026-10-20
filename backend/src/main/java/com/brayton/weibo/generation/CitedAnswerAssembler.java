package com.brayton.weibo.generation;

import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextCitation;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把生成响应的 content blocks 组装成 {@link CitedAnswer}。
 *
 * <p>开了 citations 后，响应被切成多个 text block，被引用的 block 带 {@code citations}；
 * citations 与 {@code output_config.format} 不兼容，所以只能这样从 blocks 里解析，不能要结构化输出。
 * thinking 等非 text block 直接跳过。
 *
 * <h3>document_index → postId</h3>
 * 请求里 document 的顺序就是 {@code docs} 的顺序，所以 {@code docs.get(document_index).postId()} 就是原帖。
 * 越界的下标（理论上 API 不会给）和非 char_location 的引用（纯文本 document 只会产生这一种）丢弃并告警，
 * 不让一条对不上的引用跳到错误的帖子。
 *
 * <h3>字符下标</h3>
 * API 的 {@code start/end_char_index} 按 Unicode code point 计（2026-10-20 实测：带 emoji 的帖子按 UTF-16 截会错位），
 * 而 Java 和前端 JS 的字符串下标都是 UTF-16 code unit，这里统一换算成 UTF-16。
 * 换算后截出的原文与 {@code cited_text} 不一致时只告警、保留引用——postId 是对的，错的只是高亮区间。
 */
@Slf4j
final class CitedAnswerAssembler {

    private CitedAnswerAssembler() {}

    static CitedAnswer assemble(List<ContentBlock> content, List<SourceDoc> docs) {
        List<CitedAnswer.Segment> segments = new ArrayList<>();
        // document_index → 角标编号，按首次被引用的顺序编
        Map<Long, Integer> refs = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();

        for (ContentBlock block : content) {
            if (!block.isText()) continue;
            TextBlock tb = block.asText();
            List<CitedAnswer.Citation> citations = new ArrayList<>();
            for (TextCitation c : tb.citations().orElse(List.of())) {
                if (!c.isCharLocation()) {
                    log.warn("忽略非 char_location 的引用：{}", c);
                    continue;
                }
                CitationCharLocation loc = c.asCharLocation();
                long index = loc.documentIndex();
                if (index < 0 || index >= docs.size()) {
                    log.warn("引用的 document_index={} 越界（共 {} 篇），丢弃", index, docs.size());
                    continue;
                }
                SourceDoc doc = docs.get((int) index);
                int ref = refs.computeIfAbsent(index, i -> refs.size() + 1);
                int[] range = toUtf16(doc.text(), loc.startCharIndex(), loc.endCharIndex());
                if (range == null || !doc.text().substring(range[0], range[1]).equals(loc.citedText())) {
                    log.warn("引用区间与原文对不上：post={} [{},{}) cited_text={}",
                            doc.postId(), loc.startCharIndex(), loc.endCharIndex(), loc.citedText());
                }
                citations.add(range == null
                        ? new CitedAnswer.Citation(ref, doc.postId(), loc.citedText(), -1, -1)
                        : new CitedAnswer.Citation(ref, doc.postId(), loc.citedText(), range[0], range[1]));
            }
            text.append(tb.text());
            appendSegment(segments, tb.text(), citations);
        }

        List<CitedAnswer.Source> sources = new ArrayList<>(refs.size());
        refs.forEach((index, ref) -> {
            SourceDoc doc = docs.get(index.intValue());
            sources.add(new CitedAnswer.Source(ref, doc.postId(), doc.title()));
        });
        return new CitedAnswer(text.toString(), List.copyOf(segments), List.copyOf(sources));
    }

    /** code point 区间 → UTF-16 区间；越界返回 null */
    static int[] toUtf16(String text, long start, long end) {
        int count = text.codePointCount(0, text.length());
        if (start < 0 || end < start || end > count) return null;
        return new int[]{text.offsetByCodePoints(0, (int) start), text.offsetByCodePoints(0, (int) end)};
    }

    /** 相邻的无引用段落合并，前端少渲染一堆碎片 */
    private static void appendSegment(List<CitedAnswer.Segment> segments, String text, List<CitedAnswer.Citation> citations) {
        if (citations.isEmpty() && !segments.isEmpty()) {
            CitedAnswer.Segment last = segments.get(segments.size() - 1);
            if (last.citations().isEmpty()) {
                segments.set(segments.size() - 1, new CitedAnswer.Segment(last.text() + text, List.of()));
                return;
            }
        }
        segments.add(new CitedAnswer.Segment(text, List.copyOf(citations)));
    }
}
