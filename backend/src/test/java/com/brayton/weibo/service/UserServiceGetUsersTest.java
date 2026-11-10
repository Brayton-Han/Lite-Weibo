package com.brayton.weibo.service;

import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class UserServiceGetUsersTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PostRepository postRepository = mock(PostRepository.class);
    private final UserService service = new UserService(
            userRepository, mock(JWTService.class), mock(PasswordEncoder.class),
            mock(FollowRepository.class), postRepository);

    private static User user(long id, int followerCount) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "followerCount", followerCount);
        u.setUsername("u" + id);
        return u;
    }

    @Test
    void firstPageUsesPageSizeAndBatchesPostCounts() {
        when(userRepository.findUsersPage(PageRequest.of(0, 2)))
                .thenReturn(List.of(user(5, 100), user(3, 50)));
        when(postRepository.countPostsByUserIds(List.of(5L, 3L)))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 7L}));

        List<UserResponse> users = service.getUsers(null, 2);

        assertThat(users).extracting(UserResponse::getId).containsExactly(5L, 3L);
        // 没发过帖的用户不在 group by 结果里，计为 0
        assertThat(users).extracting(UserResponse::getPostCount).containsExactly(7, 0);
        // 一页只查一次发帖数，不再逐个用户查询
        verify(postRepository, times(1)).countPostsByUserIds(anyCollection());
        verify(postRepository, never()).countPostsByUserId(anyLong());
    }

    @Test
    void nextPageContinuesFromCursorUsersFollowerCount() {
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3, 50)));
        when(userRepository.findUsersPageAfter(50, 3L, PageRequest.of(0, 20)))
                .thenReturn(List.of(user(2, 50), user(9, 10)));
        when(postRepository.countPostsByUserIds(anyCollection())).thenReturn(List.of());

        assertThat(service.getUsers(3L, 20)).extracting(UserResponse::getId).containsExactly(2L, 9L);
    }

    @Test
    void emptyPageSkipsPostCountQuery() {
        when(userRepository.findUsersPage(any())).thenReturn(List.of());

        assertThat(service.getUsers(null, 20)).isEmpty();
        verifyNoInteractions(postRepository);
    }

    @Test
    void unknownCursorIsRejected() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUsers(42L, 20)).isInstanceOf(WeiboException.class);
    }

    @Test
    void userInfosKeepRequestedOrderInOneQuery() {
        // findAllById 不保证顺序，这里故意乱序返回，并少一个已删除的用户
        when(userRepository.findAllById(List.of(3L, 1L, 2L))).thenReturn(List.of(user(2, 0), user(3, 0)));

        assertThat(service.getUserInfosInOrder(List.of(3L, 1L, 2L)))
                .extracting(UserResponse::getId).containsExactly(3L, 2L);
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void userInfosOfEmptyIdsSkipsQuery() {
        assertThat(service.getUserInfosInOrder(List.of())).isEmpty();
        verifyNoInteractions(userRepository);
    }
}
