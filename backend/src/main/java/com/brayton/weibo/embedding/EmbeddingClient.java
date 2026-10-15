package com.brayton.weibo.embedding;

import java.util.List;

/**
 * 文本向量化的统一入口。抽象成接口是为了做选型对照（Voyage vs bge-m3）：
 * 检索与评测代码只依赖这个接口，换模型不动调用方。
 *
 * <h3>为什么区分 query / document 两个方法，而不是一个 embed + 参数</h3>
 * 多数检索模型对查询和文档使用不同的前缀（Voyage 的 input_type），两边用错会显著掉点，
 * 而且不会报错。把它编码进方法名，调用方就没有机会传错。
 *
 * <h3>维度约束</h3>
 * 数据库的 {@code vector(N)} 必须等于 {@link #dimensions()}；换模型要重建向量列。
 * {@link #modelId()} 随向量一起入库，用来区分不同模型产出的向量——它们之间不可比。
 */
public interface EmbeddingClient {

    /** 向量化检索查询（Voyage input_type = query）。 */
    float[] embedQuery(String text);

    /**
     * 批量向量化查询，供离线评测一次性嵌入整个 query 集。
     * 与 {@link #embedQuery} 同一 input_type，但按入库路径的策略拆批与重试（含 429 退避）——
     * 离线工具等得起，逐条快速失败反而会被限流打断。
     */
    List<float[]> embedQueries(List<String> texts);

    /**
     * 向量化待入库的文档（Voyage input_type = document）。
     * 返回顺序与入参一一对应；实现负责按服务端上限拆批与重试。
     */
    List<float[]> embedDocuments(List<String> texts);

    int dimensions();

    String modelId();
}
