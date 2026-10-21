package com.brayton.weibo.tools;

import com.brayton.weibo.dto.AskResponse;
import com.brayton.weibo.eval.GenerationJudge;
import com.brayton.weibo.eval.GenerationMetrics;
import com.brayton.weibo.eval.GenerationMetrics.ClaimVerdict;
import com.brayton.weibo.eval.GenerationMetrics.IntentOutcome;
import com.brayton.weibo.eval.GenerationMetrics.RefusalCounts;
import com.brayton.weibo.eval.GenerationMetrics.RefusalOutcome;
import com.brayton.weibo.generation.CitedAnswer;
import com.brayton.weibo.generation.ClaudeGateway;
import com.brayton.weibo.generation.IntentClassifier;
import com.brayton.weibo.generation.QueryIntent;
import com.brayton.weibo.generation.SourceDoc;
import com.brayton.weibo.service.AskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生成层评测（RAG Day 15）：对标注集里的 query 走一遍线上问答路径，再用 {@link GenerationJudge} 判。
 *
 * 触发方式（一次性，跑完即退出）：
 *   GEN_EVAL=answers,judge ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools -Dspring-boot.run.arguments=--server.port=0
 * 需要 ANTHROPIC_API_KEY 与 VOYAGE_API_KEY，以及能连上的 PostgreSQL / Redis。
 *
 * <h3>步骤（{@code GEN_EVAL}，逗号分隔）</h3>
 * <ul>
 *   <li>{@code answers}：每条 query 调 {@link AskService#trace}——与线上 {@code /posts/search/ask} 同一条路径，
 *       额外记下意图、top-1 和送进模型的帖子，写 {@code eval/work/gen_answers_{split}.jsonl}。
 *       <b>已有的 qid 不重跑</b>：生成不确定，重跑会让人工标注对不上回答。要重新生成就删掉文件。
 *       随后导出人工校准表 {@code gen_calib_sheet_{split}.tsv} 与对照阅读用的 {@code gen_calib_context_{split}.md}，
 *       校准表已存在时不覆盖（里面可能有标了一半的人工标签）；</li>
 *   <li>{@code judge}：对 {@code mode = ANSWER} 的回答跑三项判定，写 {@code eval/work/gen_judge_{split}.jsonl}，
 *       每行一个单元，{@code unit_id} 与校准表一致。已判过的 qid 不重跑。</li>
 *   <li>{@code judge-citations}：只重判引用，句子与有用性的判定原样保留。改了引用判定的 prompt 后用；
 *       旧文件先备份为 {@code gen_judge_{split}.v1.jsonl}（已有备份时不覆盖）。</li>
 *   <li>{@code intent}：train / test 里没有风险类 query，风险类召回另用 {@code eval/intent/risk_dev.jsonl}（train）
 *       或 {@code risk_test.jsonl}（test），只过意图分类器，写 {@code eval/work/gen_intent_{risk_dev|risk_test}.jsonl}。已分过的不重跑；</li>
 *   <li>{@code summary}：不调 API，从上面几个文件算五项指标（{@link GenerationMetrics}），
 *       写 {@code eval/results/gen_summary_{split}_{时间戳}.md} 与逐 query 明细 {@code gen_{split}_{时间戳}.tsv}（只有 id 和数字，进 git）；
 *       被判为幻觉的句子与不支持的引用带原文，写 {@code eval/work/gen_findings_{split}.md}（不进 git）。</li>
 * </ul>
 * 文件都在 {@code eval/work/}（不进 git）：回答与校准表里带着帖子原文。
 *
 * <h3>环境变量</h3>
 * <ul>
 *   <li>{@code GEN_EVAL_SPLIT}：{@code train}（默认）/ {@code test}。test 只在第五条跑一次</li>
 *   <li>{@code EVAL_DIR}：默认 {@code ../eval}</li>
 * </ul>
 *
 * <h3>口径</h3>
 * 走的是线上检索，不像 {@link RagEvalRunner} 那样剔除没有 corpus_meta 的帖子：评的是用户实际会看到的回答。
 */
@Component
@Profile("tools")
@Order(GenerationEvalRunner.ORDER)
@RequiredArgsConstructor
public class GenerationEvalRunner implements CommandLineRunner {

    /** 必须显式指定且优先于 {@link ToolsShutdownRunner}，理由见该类注释。 */
    static final int ORDER = 500;

    private final AskService askService;
    private final GenerationJudge judge;
    private final IntentClassifier classifier;
    private final ClaudeGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) throws IOException {
        String steps = System.getenv("GEN_EVAL");
        if (steps == null || steps.isBlank()) {
            return;
        }
        String split = env("GEN_EVAL_SPLIT", "train");
        if (!Set.of("train", "test").contains(split)) {
            throw new IllegalArgumentException("GEN_EVAL_SPLIT 只能是 train / test：" + split);
        }
        Path evalDir = Path.of(env("EVAL_DIR", "../eval"));
        Path work = evalDir.resolve("work");
        Files.createDirectories(work);
        Path answersFile = work.resolve("gen_answers_" + split + ".jsonl");

        Set<String> requested = Set.of(steps.split(","));
        if (requested.contains("answers")) {
            generateAnswers(evalDir.resolve(split).resolve("queries.jsonl"), answersFile);
            exportSheet(answersFile, work.resolve("gen_calib_sheet_" + split + ".tsv"),
                    work.resolve("gen_calib_context_" + split + ".md"));
        }
        if (requested.contains("judge")) {
            runJudge(answersFile, work.resolve("gen_judge_" + split + ".jsonl"));
        }
        if (requested.contains("judge-citations")) {
            rejudgeCitations(answersFile, work.resolve("gen_judge_" + split + ".jsonl"),
                    work.resolve("gen_judge_" + split + ".v1.jsonl"));
        }
        String riskSet = split.equals("train") ? "risk_dev" : "risk_test";
        Path intentFile = work.resolve("gen_intent_" + riskSet + ".jsonl");
        if (requested.contains("intent")) {
            classifyRiskSet(evalDir.resolve("intent").resolve(riskSet + ".jsonl"), intentFile);
        }
        if (requested.contains("summary")) {
            summarize(split, riskSet, answersFile, work.resolve("gen_judge_" + split + ".jsonl"), intentFile,
                    evalDir.resolve("results"), work.resolve("gen_findings_" + split + ".md"));
        }
        System.out.println("claude usage: " + gateway.totals());
    }

    private void classifyRiskSet(Path queriesFile, Path out) throws IOException {
        Set<String> done = readJsonl(out).stream().map(n -> n.get("qid").asText()).collect(Collectors.toSet());
        try (BufferedWriter w = append(out)) {
            for (JsonNode q : readJsonl(queriesFile)) {
                String qid = q.get("qid").asText();
                if (done.contains(qid)) {
                    continue;
                }
                ObjectNode row = mapper.createObjectNode();
                row.put("qid", qid);
                row.put("gold", q.get("intent").asText());
                try {
                    row.put("intent", classifier.classify(q.get("query").asText()).name());
                } catch (RuntimeException e) {
                    // 分类失败照实记下：指标里算错，风险类算漏判
                    row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                w.write(mapper.writeValueAsString(row));
                w.newLine();
                w.flush();
                System.out.printf("intent %s gold=%s → %s%n", qid, row.get("gold").asText(), row.path("intent").asText("ERROR"));
            }
        }
    }

    /** 标注意图：经验层与无答案 query 都是 EXPERIENCE（"冰壶怎么计分"是具体信息需求，站内没有答案是检索层的事） */
    static QueryIntent goldIntent(JsonNode row) {
        return "resonance".equals(row.path("layer").asText()) ? QueryIntent.RESONANCE : QueryIntent.EXPERIENCE;
    }

    private static QueryIntent predicted(JsonNode row) {
        return row.hasNonNull("intent") ? QueryIntent.valueOf(row.get("intent").asText()) : null;
    }

    private void summarize(String split, String riskSet, Path answersFile, Path judgeFile, Path intentFile,
                           Path resultsDir, Path findingsFile) throws IOException {
        List<JsonNode> answers = readJsonl(answersFile);
        List<JsonNode> judged = readJsonl(judgeFile);
        List<JsonNode> riskRows = readJsonl(intentFile);
        if (answers.isEmpty() || judged.isEmpty() || riskRows.isEmpty()) {
            throw new IllegalStateException("先跑 GEN_EVAL=answers,judge,intent：缺 " +
                    (answers.isEmpty() ? answersFile : judged.isEmpty() ? judgeFile : intentFile));
        }
        Map<String, List<JsonNode>> unitsByQid = judged.stream().collect(Collectors.groupingBy(n -> n.get("qid").asText()));

        // 意图
        List<IntentOutcome> splitIntents = answers.stream().map(r -> new IntentOutcome(goldIntent(r), predicted(r))).toList();
        List<IntentOutcome> riskIntents = riskRows.stream()
                .map(r -> new IntentOutcome(QueryIntent.valueOf(r.get("gold").asText()), predicted(r))).toList();
        List<String> intentErrors = new ArrayList<>();
        for (JsonNode r : answers) {
            if (goldIntent(r) != predicted(r)) intentErrors.add(r.get("qid").asText() + " " + goldIntent(r) + "→" + r.path("intent").asText("ERROR"));
        }
        for (JsonNode r : riskRows) {
            if (!r.get("gold").asText().equals(r.path("intent").asText())) {
                intentErrors.add(r.get("qid").asText() + " " + r.get("gold").asText() + "→" + r.path("intent").asText("ERROR"));
            }
        }

        // 拒答：只看标注意图为经验类的 query；拒答 = 没有给出生成回答
        List<JsonNode> experience = answers.stream().filter(r -> goldIntent(r) == QueryIntent.EXPERIENCE).toList();
        RefusalCounts refusal = GenerationMetrics.refusalCounts(experience.stream()
                .map(r -> new RefusalOutcome(r.get("answerable").asBoolean(), "ANSWER".equals(r.path("mode").asText()))).toList());
        List<String> falseRefusals = new ArrayList<>(), missedRefusals = new ArrayList<>(), caughtNoAnswer = new ArrayList<>();
        for (JsonNode r : experience) {
            boolean answered = "ANSWER".equals(r.path("mode").asText());
            String why = r.get("qid").asText() + "（" + refusalReason(r) + "）";
            if (r.get("answerable").asBoolean() && !answered) falseRefusals.add(why);
            if (!r.get("answerable").asBoolean()) (answered ? missedRefusals : caughtNoAnswer).add(why);
        }

        // judge 三项
        List<Boolean> citations = new ArrayList<>();
        List<ClaimVerdict> claims = new ArrayList<>();
        List<Integer> usefulness = new ArrayList<>();
        int answersWithHallucination = 0, answered = 0;
        for (JsonNode r : answers) {
            if (!"ANSWER".equals(r.path("mode").asText())) continue;
            answered++;
            boolean anyU = false;
            for (JsonNode u : unitsByQid.getOrDefault(r.get("qid").asText(), List.of())) {
                String label = u.get("label").asText();
                switch (u.get("kind").asText()) {
                    case "citation" -> citations.add("1".equals(label));
                    case "sentence" -> {
                        ClaimVerdict v = claimVerdict(label);
                        claims.add(v);
                        anyU |= v == ClaimVerdict.UNSUPPORTED;
                    }
                    case "usefulness" -> usefulness.add(Integer.parseInt(label));
                    default -> throw new IllegalStateException("未知单元类型：" + u);
                }
            }
            if (anyU) answersWithHallucination++;
        }
        if (usefulness.size() != answered) {
            throw new IllegalStateException("有回答没判完：" + answered + " 条回答，" + usefulness.size() + " 个有用性分数");
        }

        double citationAcc = GenerationMetrics.citationAccuracy(citations);
        double hallucination = GenerationMetrics.hallucinationRate(claims);
        double useful = GenerationMetrics.usefulness(usefulness);
        double intentAcc = GenerationMetrics.intentAccuracy(splitIntents);
        double riskSetAcc = GenerationMetrics.intentAccuracy(riskIntents);
        double riskRecall = GenerationMetrics.riskRecall(riskIntents);
        long unsupportedClaims = claims.stream().filter(v -> v == ClaimVerdict.UNSUPPORTED).count();
        long claimCount = claims.stream().filter(v -> v != ClaimVerdict.NO_CLAIM).count();
        long riskGold = riskIntents.stream().filter(o -> o.gold() == QueryIntent.RISK).count();

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.createDirectories(resultsDir);
        Path summaryFile = resultsDir.resolve("gen_summary_" + split + "_" + ts + ".md");
        Path detailFile = resultsDir.resolve("gen_" + split + "_" + ts + ".tsv");

        List<String> md = new ArrayList<>();
        md.add("# 生成层评测 · " + split + "（" + ts + "）");
        md.add("");
        md.add("回答 `eval/work/gen_answers_" + split + ".jsonl`（" + answers.size() + " 条 query，" + answered + " 条给出回答），"
                + "judge `claude-haiku-4-5`，口径见 docs/EVAL.md「Day 15 · 生成层指标定义」。");
        md.add("");
        md.add("| 指标 | 值 | 计数 | 目标 | |");
        md.add("|---|---|---|---|---|");
        md.add(row("引用正确率", citationAcc, citations.stream().filter(b -> b).count() + " / " + citations.size() + " 条引用", "> 90%", citationAcc > 0.90));
        md.add(row("幻觉率", hallucination, unsupportedClaims + " / " + claimCount + " 句（另有 "
                + (claims.size() - claimCount) + " 句 NO_CLAIM）", "< 5%", hallucination < 0.05));
        md.add(row("拒答正确率（balanced）", refusal.accuracy(), "误拒 " + refusal.falseRefusals() + " / " + refusal.answerable()
                + "，漏拒 " + refusal.missedRefusals() + " / " + refusal.noAnswer(), "> 90%", refusal.accuracy() > 0.90));
        md.add(row("意图路由准确率（" + split + "）", intentAcc, count(splitIntents) + " 条", "> 95%", intentAcc > 0.95));
        md.add(row("意图路由准确率（" + riskSet + "）", riskSetAcc, count(riskIntents) + " 条", "> 95%", riskSetAcc > 0.95));
        md.add(row("风险类召回（" + riskSet + "）", riskRecall, Math.round(riskRecall * riskGold) + " / " + riskGold, "= 100%", riskRecall == 1.0));
        md.add(String.format("| 答案有用性 | %.2f | %d 条回答 | > 3.5 | %s |", useful, usefulness.size(), useful > 3.5 ? "✅" : "❌"));
        md.add("");
        md.add(String.format("另：%d 条回答里有 %d 条至少含一句被判为幻觉的句子。", answered, answersWithHallucination));
        md.add("");
        md.add("## 拒答明细（标注意图为经验类的 " + experience.size() + " 条）");
        md.add("");
        md.add("- 误拒：" + (falseRefusals.isEmpty() ? "无" : String.join("、", falseRefusals)));
        md.add("- 漏拒：" + (missedRefusals.isEmpty() ? "无" : String.join("、", missedRefusals)));
        md.add("- 正确拒答：" + (caughtNoAnswer.isEmpty() ? "无" : String.join("、", caughtNoAnswer)));
        md.add("");
        md.add("## 意图错误");
        md.add("");
        md.add(intentErrors.isEmpty() ? "无" : "- " + String.join("\n- ", intentErrors));
        md.add("");
        md.add("## judge 是否可信");
        md.add("");
        md.add("校准见 `eval/GEN_AGREEMENT.md`、`eval/GEN_AGREEMENT_CIT2.md`：引用 96.7%、句子幻觉 96.7%、有用性 ±1 100%，"
                + "**标注者是 Claude Opus 5.5，不是人工**；引用样本里没有负例，judge 抓错引用的能力没有验证过。");
        Files.write(summaryFile, md, StandardCharsets.UTF_8);

        try (BufferedWriter w = Files.newBufferedWriter(detailFile, StandardCharsets.UTF_8)) {
            w.write("qid\ttype\tlayer\tanswerable\tgold_intent\tintent\tmode\trefusal\ttop1\tcitations\tcitations_ok\tclaims\tunsupported\tusefulness");
            w.newLine();
            for (JsonNode r : answers) {
                List<JsonNode> us = unitsByQid.getOrDefault(r.get("qid").asText(), List.of());
                long nc = us.stream().filter(u -> "citation".equals(u.get("kind").asText())).count();
                long okc = us.stream().filter(u -> "citation".equals(u.get("kind").asText()) && "1".equals(u.get("label").asText())).count();
                long ns = us.stream().filter(u -> "sentence".equals(u.get("kind").asText()) && !"N".equals(u.get("label").asText())).count();
                long nu = us.stream().filter(u -> "sentence".equals(u.get("kind").asText()) && "U".equals(u.get("label").asText())).count();
                String use = us.stream().filter(u -> "usefulness".equals(u.get("kind").asText())).map(u -> u.get("label").asText()).findFirst().orElse("");
                w.write(String.join("\t", r.get("qid").asText(), r.get("type").asText(), r.path("layer").asText(""),
                        r.get("answerable").asText(), goldIntent(r).name(), r.path("intent").asText("ERROR"),
                        r.path("mode").asText("ERROR"), r.path("refusal").asText(""),
                        r.has("top1") ? String.format("%.4f", r.get("top1").asDouble()) : "",
                        us.isEmpty() ? "" : String.valueOf(nc), us.isEmpty() ? "" : String.valueOf(okc),
                        us.isEmpty() ? "" : String.valueOf(ns), us.isEmpty() ? "" : String.valueOf(nu), use));
                w.newLine();
            }
        }

        writeFindings(answers, unitsByQid, findingsFile);
        md.forEach(System.out::println);
        System.out.println("\n写入 " + summaryFile + "、" + detailFile + "；带原文的问题清单 " + findingsFile);
    }

    /** 被判为幻觉的句子与不支持的引用，带原文和 judge 理由，给人逐条复核；含帖子内容，只写 eval/work/ */
    private void writeFindings(List<JsonNode> answers, Map<String, List<JsonNode>> unitsByQid, Path out) throws IOException {
        Map<String, String> queries = new HashMap<>();
        Map<String, List<String>> sentences = new HashMap<>();
        for (JsonNode r : answers) {
            queries.put(r.get("qid").asText(), r.get("query").asText());
            if (r.has("answer")) {
                sentences.put(r.get("qid").asText(),
                        GenerationJudge.splitSentences(mapper.treeToValue(r.get("answer"), CitedAnswer.class).text()));
            }
        }
        List<String> lines = new ArrayList<>(List.of("# 生成层评测：被判为问题的单元", "",
                "judge 的结论不一定对，逐条复核。句子编号见 gen_calib_context 对照阅读。", ""));
        for (Map.Entry<String, List<JsonNode>> e : unitsByQid.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            List<String> items = new ArrayList<>();
            for (JsonNode u : e.getValue()) {
                String id = u.get("unit_id").asText();
                if ("sentence".equals(u.get("kind").asText()) && "U".equals(u.get("label").asText())) {
                    int idx = Integer.parseInt(id.substring(id.lastIndexOf("-s") + 2)) - 1;
                    items.add("- **" + id + " 幻觉**：" + sentences.get(e.getKey()).get(idx) + "  \n  judge：" + u.get("reason").asText());
                } else if ("citation".equals(u.get("kind").asText()) && "0".equals(u.get("label").asText())) {
                    items.add("- **" + id + " 引用不支持**：" + u.path("context").asText("") + "  \n  judge：" + u.get("reason").asText());
                }
            }
            if (!items.isEmpty()) {
                lines.add("## " + e.getKey() + "：" + queries.get(e.getKey()));
                lines.add("");
                lines.addAll(items);
                lines.add("");
            }
        }
        Files.write(out, lines, StandardCharsets.UTF_8);
    }

    private static String refusalReason(JsonNode r) {
        if (r.has("error")) return "失败：" + r.get("error").asText();
        if (!"EXPERIENCE".equals(r.path("intent").asText())) return "意图分成 " + r.path("intent").asText();
        if ("ANSWER".equals(r.path("mode").asText())) return "给出回答" + (r.has("top1") ? String.format("，top-1 %.3f", r.get("top1").asDouble()) : "");
        return r.path("refusal").asText() + (r.has("top1") ? String.format("，top-1 %.3f", r.get("top1").asDouble()) : "");
    }

    private static ClaimVerdict claimVerdict(String label) {
        return switch (label) {
            case "S" -> ClaimVerdict.SUPPORTED;
            case "U" -> ClaimVerdict.UNSUPPORTED;
            case "N" -> ClaimVerdict.NO_CLAIM;
            default -> throw new IllegalStateException("未知句子判定：" + label);
        };
    }

    private static String count(List<IntentOutcome> outcomes) {
        return outcomes.stream().filter(o -> o.gold() == o.predicted()).count() + " / " + outcomes.size();
    }

    private static String row(String name, double value, String counts, String target, boolean ok) {
        return String.format("| %s | %.1f%% | %s | %s | %s |", name, value * 100, counts, target, ok ? "✅" : "❌");
    }

    private void generateAnswers(Path queriesFile, Path out) throws IOException {
        Set<String> done = readJsonl(out).stream().map(n -> n.get("qid").asText()).collect(Collectors.toSet());
        List<JsonNode> queries = readJsonl(queriesFile);
        int fresh = 0;
        try (BufferedWriter w = append(out)) {
            for (JsonNode q : queries) {
                String qid = q.get("qid").asText();
                if (done.contains(qid)) {
                    continue;
                }
                ObjectNode row = mapper.createObjectNode();
                row.put("qid", qid);
                row.put("query", q.get("query").asText());
                row.put("type", q.get("type").asText());
                row.set("layer", q.get("layer"));
                row.put("answerable", !q.get("relevant").isEmpty());
                try {
                    AskService.Trace t = askService.trace(q.get("query").asText(), null);
                    AskResponse r = t.response();
                    row.put("intent", t.intent().name());
                    if (!Double.isNaN(t.top1())) {
                        row.put("top1", t.top1());
                    }
                    row.put("mode", r.getMode().name());
                    if (r.getRefusal() != null) {
                        row.put("refusal", r.getRefusal().name());
                    }
                    row.set("docs", mapper.valueToTree(t.docs()));
                    if (r.getAnswer() != null) {
                        row.set("answer", mapper.valueToTree(r.getAnswer()));
                    }
                } catch (RuntimeException e) {
                    // 分类或生成失败照实记下，不跳过：意图指标里分类失败算错
                    row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                w.write(mapper.writeValueAsString(row));
                w.newLine();
                w.flush();
                fresh++;
                System.out.printf("%s %-10s %s%n", qid, row.path("mode").asText("ERROR"), q.get("query").asText());
            }
        }
        System.out.printf("answers：新生成 %d 条，沿用 %d 条 → %s%n", fresh, done.size(), out);
    }

    /** 校准单元：每条引用（c）、每个句子（s）、每个回答的有用性（u），unit_id 形如 q001-c2 */
    record Unit(String unitId, String qid, String kind, String text, String citedText) {}

    static List<Unit> units(String qid, CitedAnswer answer) {
        List<Unit> out = new ArrayList<>();
        int c = 0;
        for (CitedAnswer.Segment seg : answer.segments()) {
            for (CitedAnswer.Citation cit : seg.citations()) {
                out.add(new Unit(qid + "-c" + (++c), qid, "citation", seg.text(), cit.citedText()));
            }
        }
        List<String> sentences = GenerationJudge.splitSentences(answer.text());
        for (int i = 0; i < sentences.size(); i++) {
            out.add(new Unit(qid + "-s" + (i + 1), qid, "sentence", sentences.get(i), ""));
        }
        out.add(new Unit(qid + "-u", qid, "usefulness", "", ""));
        return out;
    }

    private void exportSheet(Path answersFile, Path sheet, Path context) throws IOException {
        if (Files.exists(sheet)) {
            System.out.println("校准表已存在，不覆盖：" + sheet);
            return;
        }
        List<JsonNode> answered = readJsonl(answersFile).stream().filter(n -> "ANSWER".equals(n.path("mode").asText())).toList();
        try (BufferedWriter w = Files.newBufferedWriter(sheet, StandardCharsets.UTF_8);
             BufferedWriter md = Files.newBufferedWriter(context, StandardCharsets.UTF_8)) {
            w.write("unit_id\tqid\tkind\ttext\tcited_text\tlabel\tnote");
            w.newLine();
            md.write("# 生成层校准：对照阅读\n\n标签填在 `" + sheet.getFileName() + "` 的 label 列，口径见 docs/EVAL.md「Day 15 · 生成层指标定义」。\n");
            for (JsonNode row : answered) {
                String qid = row.get("qid").asText();
                CitedAnswer answer = mapper.treeToValue(row.get("answer"), CitedAnswer.class);
                List<SourceDoc> docs = List.of(mapper.treeToValue(row.get("docs"), SourceDoc[].class));
                for (Unit u : units(qid, answer)) {
                    w.write(String.join("\t", u.unitId(), u.qid(), u.kind(), tsv(u.text()), tsv(u.citedText()), "", ""));
                    w.newLine();
                }
                md.write("\n---\n\n## " + qid + "：" + row.get("query").asText() + "\n\n### 回答\n\n");
                List<String> sentences = GenerationJudge.splitSentences(answer.text());
                for (int i = 0; i < sentences.size(); i++) {
                    md.write("- **s" + (i + 1) + "** " + sentences.get(i) + "\n");
                }
                md.write("\n### 引用\n\n");
                int c = 0;
                for (CitedAnswer.Segment seg : answer.segments()) {
                    for (CitedAnswer.Citation cit : seg.citations()) {
                        md.write("- **c" + (++c) + "** 「" + seg.text().strip() + "」 ← `" + cit.postId() + "` " + cit.citedText() + "\n");
                    }
                }
                md.write("\n### 送进模型的帖子（" + docs.size() + " 条）\n\n");
                for (int i = 0; i < docs.size(); i++) {
                    SourceDoc d = docs.get(i);
                    md.write((i + 1) + ". `" + d.postId() + "` " + d.title() + "：" + d.text().replace("\n", " ") + "\n");
                }
            }
        }
        System.out.printf("校准表：%d 条回答 → %s（对照阅读 %s）%n", answered.size(), sheet, context);
    }

    private void runJudge(Path answersFile, Path out) throws IOException {
        Set<String> done = readJsonl(out).stream().map(n -> n.get("qid").asText()).collect(Collectors.toSet());
        int fresh = 0;
        try (BufferedWriter w = append(out)) {
            for (JsonNode row : readJsonl(answersFile)) {
                String qid = row.get("qid").asText();
                if (!"ANSWER".equals(row.path("mode").asText()) || done.contains(qid)) {
                    continue;
                }
                String query = row.get("query").asText();
                CitedAnswer answer = mapper.treeToValue(row.get("answer"), CitedAnswer.class);
                List<SourceDoc> docs = List.of(mapper.treeToValue(row.get("docs"), SourceDoc[].class));

                // 先全部判完再写，一条回答的单元要么都在、要么都不在，断点续跑不会留半条
                List<Map<String, Object>> lines = new ArrayList<>();
                List<GenerationJudge.CitationVerdict> citations = judge.judgeCitations(query, answer);
                for (int i = 0; i < citations.size(); i++) {
                    GenerationJudge.CitationVerdict v = citations.get(i);
                    lines.add(line(qid, qid + "-c" + (i + 1), "citation", v.supported() ? "1" : "0", v.reason()));
                }
                List<GenerationJudge.SentenceVerdict> sentences = judge.judgeClaims(query, docs, answer);
                for (int i = 0; i < sentences.size(); i++) {
                    GenerationJudge.SentenceVerdict v = sentences.get(i);
                    lines.add(line(qid, qid + "-s" + (i + 1), "sentence", v.verdict().name().substring(0, 1), v.reason()));
                }
                GenerationJudge.Usefulness u = judge.judgeUsefulness(query, docs, answer);
                lines.add(line(qid, qid + "-u", "usefulness", String.valueOf(u.score()), u.reason()));

                for (Map<String, Object> l : lines) {
                    w.write(mapper.writeValueAsString(l));
                    w.newLine();
                }
                w.flush();
                fresh++;
                System.out.printf("judge %s：%d 个单元%n", qid, lines.size());
            }
        }
        System.out.printf("judge：新判 %d 条回答，沿用 %d 条 → %s%n", fresh, done.size(), out);
    }

    private void rejudgeCitations(Path answersFile, Path judgeFile, Path backup) throws IOException {
        if (!Files.exists(judgeFile)) {
            throw new IllegalStateException("没有 " + judgeFile + "，先跑 GEN_EVAL=judge");
        }
        if (!Files.exists(backup)) {
            Files.copy(judgeFile, backup);
        }
        List<JsonNode> kept = readJsonl(judgeFile).stream().filter(n -> !"citation".equals(n.get("kind").asText())).toList();
        List<Map<String, Object>> fresh = new ArrayList<>();
        for (JsonNode row : readJsonl(answersFile)) {
            if (!"ANSWER".equals(row.path("mode").asText())) {
                continue;
            }
            String qid = row.get("qid").asText();
            CitedAnswer answer = mapper.treeToValue(row.get("answer"), CitedAnswer.class);
            List<GenerationJudge.CitationVerdict> citations = judge.judgeCitations(row.get("query").asText(), answer);
            for (int i = 0; i < citations.size(); i++) {
                GenerationJudge.CitationVerdict v = citations.get(i);
                Map<String, Object> l = line(qid, qid + "-c" + (i + 1), "citation", v.supported() ? "1" : "0", v.reason());
                l.put("context", v.context());
                fresh.add(l);
            }
            System.out.printf("judge-citations %s：%d 条%n", qid, citations.size());
        }
        // 全部判完再整体写回，中途失败不会留下半新半旧的文件
        try (BufferedWriter w = Files.newBufferedWriter(judgeFile, StandardCharsets.UTF_8)) {
            for (JsonNode n : kept) {
                w.write(mapper.writeValueAsString(n));
                w.newLine();
            }
            for (Map<String, Object> l : fresh) {
                w.write(mapper.writeValueAsString(l));
                w.newLine();
            }
        }
        System.out.printf("judge-citations：重判 %d 条引用，保留 %d 条其他判定 → %s（旧版 %s）%n",
                fresh.size(), kept.size(), judgeFile, backup);
    }

    private static Map<String, Object> line(String qid, String unitId, String kind, String label, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit_id", unitId);
        m.put("qid", qid);
        m.put("kind", kind);
        m.put("label", label);
        m.put("reason", reason);
        return m;
    }

    private List<JsonNode> readJsonl(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!l.isBlank()) {
                out.add(mapper.readTree(l));
            }
        }
        return out;
    }

    private static BufferedWriter append(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String tsv(String s) {
        return s == null ? "" : s.replace("\t", " ").replace("\r", " ").replace("\n", " ⏎ ");
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.strip();
    }
}
