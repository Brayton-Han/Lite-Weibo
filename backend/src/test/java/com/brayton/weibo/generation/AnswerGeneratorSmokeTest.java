package com.brayton.weibo.generation;

import com.brayton.weibo.config.ClaudeConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实调用 Claude 的冒烟测试，验证 citations 的真实形状与 {@link CitedAnswerAssembler} 的假设一致（RAG_ROADMAP Day 14）。
 * 未设置 ANTHROPIC_API_KEY 时自动跳过，不影响 ./mvnw test。用 Haiku，单次约 $0.001。
 *
 * <p>帖子带 emoji 是故意的：API 的字符下标按 code point 计，assembler 换算成 UTF-16 后必须能逐字截回 cited_text。
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnswerGeneratorSmokeTest {

    private static final ClaudeGateway gateway =
            new ClaudeGateway(new ClaudeConfig().anthropicClient(System.getenv("ANTHROPIC_API_KEY"), 60000, 2));
    private final AnswerGenerator generator =
            new AnswerGenerator(new AnswerPromptBuilder("claude-haiku-5-5", 16000), gateway);

    private static final List<SourceDoc> DOCS = List.of(
            new SourceDoc(101, "@alice · 2026-03-01", "😤北京租房押一付三是最常见的，中介一般还要收一个月租金当中介费。"),
            new SourceDoc(202, "@bob · 2026-03-02", "我在朝阳跟房东直租，谈下来押一付一，没有中介费🎉"),
            new SourceDoc(303, "@carol · 2026-03-03", "今天中午吃了楼下的牛肉面，还行。"));

    @AfterAll
    static void printUsage() {
        System.out.println("usage: " + gateway.totals());
    }

    @Test
    void citationsMapBackToTheRightPosts() {
        CitedAnswer answer = generator.generate("北京租房一般押几付几？有中介费吗？", DOCS).answer();
        Map<Long, SourceDoc> byId = DOCS.stream().collect(Collectors.toMap(SourceDoc::postId, Function.identity()));

        System.out.println("answer: " + answer.text());
        for (CitedAnswer.Segment seg : answer.segments()) {
            for (CitedAnswer.Citation c : seg.citations()) {
                assertThat(byId).containsKey(c.postId());
                String body = byId.get(c.postId()).text();
                System.out.printf("[%d] post=%d [%d,%d) cited=%s%n",
                        c.ref(), c.postId(), c.startChar(), c.endChar(), c.citedText());
                assertThat(body.substring((int) c.startChar(), (int) c.endChar())).isEqualTo(c.citedText());
            }
        }

        assertThat(answer.hasCitations()).isTrue();
        assertThat(answer.sources()).extracting(CitedAnswer.Source::postId)
                .contains(101L, 202L)
                .doesNotContain(303L);
    }

    @Test
    void unanswerableQuestionSaysSoWithoutCitations() {
        CitedAnswer answer = generator.generate("上海落户需要几年社保？", DOCS).answer();
        System.out.println("unanswerable: " + answer.text() + " sources=" + answer.sources());

        assertThat(answer.text()).contains("站内没有找到相关讨论");
        assertThat(answer.hasCitations()).isFalse();
    }
}
