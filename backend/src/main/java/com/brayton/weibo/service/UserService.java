package com.brayton.weibo.service;

import com.brayton.weibo.config.security.JWTService;
import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final JWTService jWTService;
    private final PasswordEncoder passwordEncoder;

    public void register(RegisterRequest request) {

        // 1. 检查用户名或邮箱是否已存在
        if (userRepository.existsByUsernameOrEmail(request.getUsername(), request.getEmail())) {
            throw new WeiboException(CommonErrorCode.USER_EXISTS);
        }

        // 2. 处理密码
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // 3. 保存用户
        User newUser = new User(request.getUsername(), encodedPassword, request.getEmail());
        userRepository.save(newUser);
    }

    public String login(LoginRequest request) {

        // 1. 查找用户
        Optional<User> userOptional = userRepository.findByUsername(request.getUsername());
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }
        User user = userOptional.get();

        // 2. 验证密码 (实际应用中需要用 passwordEncoder.matches(rawPassword, encodedPassword) 验证)
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHashed())) { // 临时简化处理
            throw new WeiboException(CommonErrorCode.INVALID_PASSWORD);
        }

        return jWTService.generateToken(user.getId());
    }

    public UserResponse getUserInfoById(long id) {

        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return new UserResponse(userOptional.get());
    }

    public UserResponse getUserInfoByUsername(String username) {

        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return new UserResponse(userOptional.get());
    }

    public String getUsernameById(long id) {

        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new WeiboException(CommonErrorCode.USER_NOT_FOUND);
        }

        return userOptional.get().getUsername();
    }
}
