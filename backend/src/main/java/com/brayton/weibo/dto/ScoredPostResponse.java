package com.brayton.weibo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 语义搜索结果：帖子 + 与查询的余弦相似度。分数随查询长短漂移，只能用于同一次查询内的排序，不能当固定阈值。 */
@Data
@AllArgsConstructor
public class ScoredPostResponse {

    private PostResponse post;

    private double score;
}
