package com.brayton.weibo.service;

import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserServiceLoginTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UserService service = new UserService(
            userRepository, mock(JWTService.class), encoder,
            mock(FollowRepository.class), mock(PostRepository.class));

    private static LoginRequest login(String username, String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }

    @Test
    void unknownUserAndWrongPasswordGiveSameError() {
        when(encoder.encode(anyString())).thenReturn("$dummy");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        User alice = new User("alice", "$real", "a@b.com");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(login("ghost", "whatever")))
                .isInstanceOfSatisfying(WeiboException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode())
                                .isEqualTo(CommonErrorCode.INVALID_CREDENTIALS.getCode()));
        assertThatThrownBy(() -> service.login(login("alice", "wrong")))
                .isInstanceOfSatisfying(WeiboException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode())
                                .isEqualTo(CommonErrorCode.INVALID_CREDENTIALS.getCode()));
    }

    @Test
    void unknownUserStillRunsPasswordCheckToEqualizeTiming() {
        when(encoder.encode(anyString())).thenReturn("$dummy");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(login("ghost", "pw"))).isInstanceOf(WeiboException.class);
        assertThatThrownBy(() -> service.login(login("ghost", "pw"))).isInstanceOf(WeiboException.class);

        verify(encoder, times(2)).matches(eq("pw"), eq("$dummy"));
        // 假哈希只生成一次
        verify(encoder, times(1)).encode(anyString());
    }
}
