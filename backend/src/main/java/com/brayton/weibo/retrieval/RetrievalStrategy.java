package com.brayton.weibo.retrieval;

import java.util.List;

/**
 * 可插拔的检索方案，评测（{@code RagEvalRunner}）与之后的混合检索只依赖这个接口。
 *
 * 约定：返回至多 k 条、按相关性从高到低（{@link LikeStrategy} 例外，它保持线上的时间排序）、
 * 只含未删除且对 requester 可见的帖子；同一帖子不重复出现。
 */
public interface RetrievalStrategy {

    /** 出现在结果文件名与汇总表里，只用小写字母、数字与连字符。 */
    String name();

    /**
     * @param requesterId 当前用户；null 表示匿名，只看 PUBLIC。
     *                    目前三条基线都只检索 PUBLIC 帖子（与向量入库口径一致），这个参数留给将来按关注关系放宽可见性
     */
    List<ScoredPost> retrieve(String query, int k, Long requesterId);
}
