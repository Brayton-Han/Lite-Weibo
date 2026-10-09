package com.brayton.weibo.event;

/**
 * 帖子的向量可能需要同步（新建、编辑、可见性变化）。
 *
 * 只带 postId：监听器在提交后按库里的最新状态取正文与原帖，
 * 而不是信任发布时刻的快照——两者之间帖子可能已被删除或改了可见性。
 * 不继承 {@link Event}，原因同 {@link PostFanOutEvent}。
 */
public record PostEmbeddingEvent(long postId) {
}
