package com.brayton.weibo.generation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 意图分类器不可用时的风险兜底（RAG Day 17）：纯本地关键词匹配，不调任何外部服务。
 *
 * <h3>为什么需要它</h3>
 * Day 16 的口径是意图未知就不展示任何帖子，免得风险类 query 先看到一屏同样绝望的帖子——代价是 Claude 一挂，搜索整页不可用。
 * Day 17 要求"Claude 挂了搜索还能用"，两者只能靠一个不依赖 Claude 的风险判断调和：命中就照风险类处理（只给热线），
 * 没命中才降级成检索结果。
 *
 * <h3>局限</h3>
 * 词表召回不可能比分类器高：隐晦的说法（"我消失了大家会过得更好"）只能靠列举，谐音黑话基本抓不到。
 * 所以它只是降级时的兜底，分类器可用时完全不参与路由。宁可误判：命中的代价只是这一次看不到帖子、看到热线，
 * 所以"加班加到想死"这种夸张说法也会命中。召回与误判在 EVAL.md「Day 17 · 风险词表」。
 */
public final class RiskKeywordGuard {

    private RiskKeywordGuard() {}

    /** 小写、去空白后做子串匹配 */
    static final List<String> PHRASES = List.of(
            // 直说
            "自杀", "自尽", "轻生", "寻死", "不想活", "活不下去", "不活了", "活着没意思", "活着没有意义", "活着好累",
            "生无可恋", "想死", "去死", "死了算了", "死了就好", "一了百了", "了结自己", "结束生命", "结束自己",
            "结束这一切", "离开这个世界", "不想醒来", "不再醒来", "永远睡", "遗书", "遗言",
            // 自伤
            "自残", "自伤", "伤害自己", "割腕", "割自己",
            // 方式
            "跳楼", "上吊", "烧炭", "安眠药", "服毒", "喝农药", "百草枯",
            // 隐晦：自我否定 + 消失
            "我消失了", "我不在了", "没有我会更好", "是家里的负担",
            // 英文
            "suicide", "kill myself", "end my life", "want to die", "self harm", "self-harm");

    /** 列举不完的句式：跳下去 / 怎么死 / 吃多少…会死 */
    static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(几|多高的?)楼.{0,4}跳"),
            Pattern.compile("跳下去"),
            Pattern.compile("(怎么|怎样|如何)(才能)?(去)?死"),
            Pattern.compile("(吃|喝|吞)多少.{0,8}(会死|致死|能死)"));

    public static boolean flags(String query) {
        if (query == null) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String spaced = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String p : PHRASES) {
            // 英文词组带空格，对保留空格的版本匹配
            if (q.contains(p) || spaced.contains(p)) {
                return true;
            }
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(q).find()) {
                return true;
            }
        }
        return false;
    }
}
