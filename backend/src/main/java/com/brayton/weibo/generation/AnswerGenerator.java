package com.brayton.weibo.generation;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生成层：用检索结果回答经验类 query。只该在意图为 {@link QueryIntent#EXPERIENCE} 且检索有结果时调用。
 *
 * <p>回答被截断或被拒答时抛 {@link ClaudeCallException}（{@code BAD_RESPONSE}），不把半截回答交出去。
 * 回答按 citations 切段并映射回 postId（见 {@link CitedAnswerAssembler}）；{@code raw} 留给评测和排查。
 */
@Component
public class AnswerGenerator {

    private final AnswerPromptBuilder prompts;
    private final ClaudeGateway gateway;

    public AnswerGenerator(AnswerPromptBuilder prompts, ClaudeGateway gateway) {
        this.prompts = prompts;
        this.gateway = gateway;
    }

    public record GeneratedAnswer(CitedAnswer answer, Message raw) {}

    /** @param docs 通常来自 {@link SourceDocLoader}，顺序即 document_index */
    public GeneratedAnswer generate(String question, List<SourceDoc> docs) {
        Message message = gateway.create("answer", prompts.build(question, docs));
        StopReason stop = message.stopReason().orElse(null);
        if (stop != StopReason.END_TURN) {
            throw new ClaudeCallException(ClaudeCallException.Kind.BAD_RESPONSE,
                    "生成未正常结束，stop_reason=" + stop);
        }
        CitedAnswer answer = CitedAnswerAssembler.assemble(message.content(), docs);
        if (answer.text().isBlank()) {
            throw new ClaudeCallException(ClaudeCallException.Kind.BAD_RESPONSE, "生成结果没有文本");
        }
        return new GeneratedAnswer(answer, message);
    }
}
