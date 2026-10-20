package com.brayton.weibo.generation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CitedAnswerAssemblerTest {

    private static final List<SourceDoc> DOCS = List.of(
            new SourceDoc(11, "@alice · 2026-03-01", "北京租房押一付三很常见。"),
            new SourceDoc(22, "@bob · 2026-03-02", "我在朝阳租的是押一付一。"),
            new SourceDoc(33, "@carol · 2026-03-03", "从没被检索命中引用的帖子"));

    /** 真实响应的形状：thinking + 交替的无引用 / 有引用 text block */
    private static final String CONTENT = """
            [{"type":"thinking","thinking":"想一下","signature":"sig"},
             {"type":"text","text":"北京常见的是","citations":null},
             {"type":"text","text":"押一付三","citations":[
                {"type":"char_location","cited_text":"北京租房押一付三很常见。","document_index":0,
                 "document_title":"@alice · 2026-03-01","start_char_index":0,"end_char_index":12,"file_id":null}]},
             {"type":"text","text":"，","citations":null},
             {"type":"text","text":"也有","citations":null},
             {"type":"text","text":"押一付一的","citations":[
                {"type":"char_location","cited_text":"我在朝阳租的是押一付一。","document_index":1,
                 "document_title":"@bob · 2026-03-02","start_char_index":0,"end_char_index":12,"file_id":null},
                {"type":"char_location","cited_text":"押一付三很常见。","document_index":0,
                 "document_title":"@alice · 2026-03-01","start_char_index":4,"end_char_index":12,"file_id":null}]},
             {"type":"text","text":"。","citations":[
                {"type":"char_location","cited_text":"不存在","document_index":7,
                 "document_title":"?","start_char_index":0,"end_char_index":3,"file_id":null}]}]
            """;

    @Test
    void mapsDocumentIndexBackToPostIdAndNumbersRefsByFirstUse() throws Exception {
        CitedAnswer answer = CitedAnswerAssembler.assemble(message(CONTENT).content(), DOCS);

        assertEquals("北京常见的是押一付三，也有押一付一的。", answer.text());

        List<CitedAnswer.Segment> segs = answer.segments();
        assertEquals(List.of("北京常见的是", "押一付三", "，也有", "押一付一的", "。"),
                segs.stream().map(CitedAnswer.Segment::text).toList(), "相邻无引用段应合并");

        CitedAnswer.Citation first = segs.get(1).citations().get(0);
        assertEquals(new CitedAnswer.Citation(1, 11, "北京租房押一付三很常见。", 0, 12), first);

        List<CitedAnswer.Citation> second = segs.get(3).citations();
        assertEquals(List.of(2, 1), second.stream().map(CitedAnswer.Citation::ref).toList());
        assertEquals(List.of(22L, 11L), second.stream().map(CitedAnswer.Citation::postId).toList());

        assertTrue(segs.get(4).citations().isEmpty(), "越界的 document_index 必须丢弃，不能跳到错误的帖子");

        // 只列被引用过的帖子，按首次引用排序；33 没被引用
        assertEquals(List.of(
                new CitedAnswer.Source(1, 11, "@alice · 2026-03-01"),
                new CitedAnswer.Source(2, 22, "@bob · 2026-03-02")), answer.sources());
        assertTrue(answer.hasCitations());
    }

    @Test
    void convertsCodePointIndicesToUtf16() throws Exception {
        // API 按 code point 计：😤 算 1 个字符，在 Java 里占 2 个 char
        List<SourceDoc> docs = List.of(new SourceDoc(1, "@a", "😤押一付三，😤中介费一个月"));
        CitedAnswer answer = CitedAnswerAssembler.assemble(message("""
                [{"type":"text","text":"要中介费","citations":[
                   {"type":"char_location","cited_text":"中介费一个月","document_index":0,
                    "document_title":"@a","start_char_index":7,"end_char_index":13,"file_id":null}]}]
                """).content(), docs);

        CitedAnswer.Citation c = answer.segments().get(0).citations().get(0);
        assertEquals(9, c.startChar());
        assertEquals(15, c.endChar());
        assertEquals("中介费一个月", docs.get(0).text().substring((int) c.startChar(), (int) c.endChar()));
    }

    @Test
    void answerWithoutCitationsHasNoSources() throws Exception {
        CitedAnswer answer = CitedAnswerAssembler.assemble(message("""
                [{"type":"text","text":"站内没有找到相关讨论","citations":null}]
                """).content(), DOCS);

        assertEquals("站内没有找到相关讨论", answer.text());
        assertFalse(answer.hasCitations());
    }

    @Test
    void generatorReturnsCitedAnswerAndRejectsTruncation() throws Exception {
        AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        AnswerGenerator generator = new AnswerGenerator(
                new AnswerPromptBuilder("claude-haiku-5-5", 16000), new ClaudeGateway(client));

        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message(CONTENT));
        assertEquals(List.of(11L, 22L), generator.generate("北京租房押几付几", DOCS).answer()
                .sources().stream().map(CitedAnswer.Source::postId).toList());

        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message(CONTENT, "max_tokens"));
        ClaudeCallException e = assertThrows(ClaudeCallException.class,
                () -> generator.generate("北京租房押几付几", DOCS));
        assertEquals(ClaudeCallException.Kind.BAD_RESPONSE, e.kind());
    }

    private static Message message(String content) throws Exception {
        return message(content, "end_turn");
    }

    private static Message message(String content, String stopReason) throws Exception {
        return ObjectMappers.jsonMapper().readValue("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """.formatted(content, stopReason), Message.class);
    }
}
