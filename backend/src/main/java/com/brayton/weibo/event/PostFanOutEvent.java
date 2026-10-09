package com.brayton.weibo.event;

import com.brayton.weibo.enums.PostVisibility;

/**
 * 帖子需要扇出到 feed（新发布 / 可见性放宽）。
 *
 * 不继承 {@link Event}：NotificationListener 监听的是 Event 基类，
 * 继承后会被它一并接收，而它的 switch 里没有对应分支。
 *
 * 只携带原始值，监听器在异步线程执行，实体跨线程会触发 LazyInitializationException。
 */
public record PostFanOutEvent(Long postId, Long authorId, PostVisibility visibility, long timestamp) {
}
