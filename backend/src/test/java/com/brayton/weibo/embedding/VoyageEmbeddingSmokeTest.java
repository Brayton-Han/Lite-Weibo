package com.brayton.weibo.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实调用 Voyage 的冒烟测试，验证 embedding 的语义性质（RAG_ROADMAP Day 4 验收）。
 * 未设置 VOYAGE_API_KEY 时自动跳过，不影响 ./mvnw test。
 *
 * 断言失败时先查 input_type（query/document 是否用反）和维度配置，再怀疑模型。
 */
@EnabledIfEnvironmentVariable(named = "VOYAGE_API_KEY", matches = ".+")
class VoyageEmbeddingSmokeTest {

    private final VoyageEmbeddingClient client = new VoyageEmbeddingClient(
            "https://api.voyageai.com", System.getenv("VOYAGE_API_KEY"), "voyage-4", 1024, 128, 5);

    @Test
    void relatedDocumentIsCloserThanUnrelatedOne() {
        float[] q = client.embedQuery("怎么实现分布式锁");
        List<float[]> docs = client.embedDocuments(List.of("Redisson 加锁的坑", "今天中午吃什么"));

        double related = cosine(q, docs.get(0));
        double unrelated = cosine(q, docs.get(1));
        System.out.printf("sim(related)=%.4f  sim(unrelated)=%.4f%n", related, unrelated);

        assertThat(q).hasSize(1024);
        assertThat(related).isGreaterThan(unrelated);
    }

    @Test
    void emotionalPostsAreCloseToClinicalTerm() {
        // 共鸣类的典型难点：query 是书面术语，帖子是口语描述，两者词面零重叠
        float[] q = client.embedQuery("抑郁");
        List<float[]> docs = client.embedDocuments(List.of(
                "今天情绪很低落，做什么都提不起劲",
                "感受不到快乐，一想到未来就好绝望",
                "今天中午吃什么"));

        double lowMood = cosine(q, docs.get(0));
        double hopeless = cosine(q, docs.get(1));
        double unrelated = cosine(q, docs.get(2));
        System.out.printf("sim(低落)=%.4f  sim(绝望)=%.4f  sim(无关)=%.4f%n", lowMood, hopeless, unrelated);

        assertThat(lowMood).isGreaterThan(unrelated);
        assertThat(hopeless).isGreaterThan(unrelated);
    }

    @Test
    void vectorsAreUnitNormalized() {
        // Voyage 输出已归一化——成立则余弦相似度 == 内积，Day 5 可以用 <#> 代替 <=>
        float[] v = client.embedQuery("撑不下去了");
        assertThat(Math.sqrt(dot(v, v))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
    }

    private static double cosine(float[] a, float[] b) {
        return dot(a, b) / (Math.sqrt(dot(a, a)) * Math.sqrt(dot(b, b)));
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
