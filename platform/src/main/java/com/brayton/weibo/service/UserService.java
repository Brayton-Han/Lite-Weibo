package com.brayton.weibo.service;

import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    // 构造函数注入
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void register(RegisterRequest request) {

        // 1. 检查用户名或邮箱是否已存在
        if (userRepository.existsByUsernameOrEmail(request.getUsername(), request.getEmail())) {
            throw new RuntimeException("Username or email already taken.");
        }

        // 2. 处理密码（真实项目是加密，这里简化）
        String encodedPassword = request.getPassword();

        // 3. 保存用户
        User newUser = new User(request.getUsername(), encodedPassword, request.getEmail());
        userRepository.save(newUser);
    }

    public String login(LoginRequest request) {

        // 1. 查找用户
        Optional<User> userOptional = userRepository.findByUsername(request.getUsername());
        if (userOptional.isEmpty()) {
            throw new RuntimeException("Username not found.");
        }
        User user = userOptional.get();

        // 2. 验证密码 (实际应用中需要用 passwordEncoder.matches(rawPassword, encodedPassword) 验证)
        if (!user.getPasswordHashed().equals(request.getPassword())) { // 临时简化处理
            throw new RuntimeException("Invalid password.");
        }

        return "Login successful! Welcome, " + user.getUsername();
    }

    public UserResponse getCurrentUserInfo() {

        // ======================================================
        // !!! 认证用户 ID 获取占位符 !!!
        // 在实际应用中，你需要从 Spring Security 上下文、JWT Token 或 Session 中
        // 获取当前登录用户的 ID。
        // 示例：long authenticatedUserId = securityContext.getAuthenticatedUser().getId();
        long authenticatedUserId = 1L; // <-- 请替换为真实的认证逻辑
        // ======================================================

        Optional<User> userOptional = userRepository.findById(authenticatedUserId);
        if (userOptional.isEmpty()) {
            // 如果认证 ID 存在，但数据库中找不到用户，返回 404
            throw new RuntimeException("Authenticated user not found.");
        }

        return new UserResponse(userOptional.get());
    }

    public UserResponse getUserInfoById(long id) {
        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new RuntimeException("User not found.");
        }

        return new UserResponse(userOptional.get());
    }
}
