package com.brayton.weibo.service;

import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import com.brayton.weibo.webSocket.WebSocketPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedWriteServicePullModeTest {

    private static final long AUTHOR = 1L;

    private final FollowRepository followRepository = mock(FollowRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RedisService redisService = mock(RedisService.class);
    private final WebSocketPusher wsPusher = mock(WebSocketPusher.class);
    private final FeedWriteService service = new FeedWriteService(
            followRepository, mock(PostRepository.class), userRepository, redisService, wsPusher);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "pullThreshold", 100L);
        when(followRepository.findFollowerIds(AUTHOR)).thenReturn(Set.of(2L, 3L));
        when(followRepository.findFriendIds(AUTHOR)).thenReturn(Set.of(2L));
    }

    @Test
    void smallAuthorPushesToFollowers() {
        author(10, false);

        service.fanOutToFeed(9L, AUTHOR, PostVisibility.PUBLIC, 1000L, true);

        verify(redisService).addToFeed(2L, 9L, 1000L);
        verify(redisService).addToFeed(3L, 9L, 1000L);
        verify(redisService, never()).addToOutbox(anyLong(), anyLong(), anyLong());
        verify(wsPusher).notifyUserNewPost(2L);
    }

    @Test
    void bigVWritesOnlyOutboxAndOwnFeed() {
        author(10, true);

        service.fanOutToFeed(9L, AUTHOR, PostVisibility.FOLLOWERS, 1000L, true);

        verify(redisService).addToOutbox(AUTHOR, 9L, 1000L);
        verify(redisService).addToFeed(AUTHOR, 9L, 1000L);
        verify(redisService, never()).addToFeed(eq(2L), anyLong(), anyLong());
        verifyNoInteractions(wsPusher);
    }

    @Test
    void crossingThresholdMarksAuthorAsPull() {
        author(100, false);

        service.fanOutToFeed(9L, AUTHOR, PostVisibility.PUBLIC, 1000L, true);

        verify(userRepository).markFeedPull(AUTHOR);
        verify(redisService).addToOutbox(AUTHOR, 9L, 1000L);
    }

    @Test
    void bigVFriendsOnlyPostIsStillPushed() {
        author(10, true);

        service.fanOutToFeed(9L, AUTHOR, PostVisibility.FRIENDS, 1000L, true);

        verify(redisService).addToFeed(2L, 9L, 1000L);
        verify(redisService, never()).addToOutbox(anyLong(), anyLong(), anyLong());
    }

    private void author(int followers, boolean pull) {
        User u = new User("a", "x", "a@example.com");
        ReflectionTestUtils.setField(u, "id", AUTHOR);
        ReflectionTestUtils.setField(u, "followerCount", followers);
        ReflectionTestUtils.setField(u, "feedPull", pull);
        when(userRepository.findById(AUTHOR)).thenReturn(Optional.of(u));
    }
}
