package com.brayton.weibo.event;

/**
 * 取关已提交，需要把被取关者的帖子从取关者的收件箱里清掉。
 *
 * 不继承 {@link Event}：NotificationListener 监听的是 Event 基类，取关不发通知。
 */
public record UnfollowEvent(Long followerId, Long followingId) {
}
