package com.brayton.weibo.controller;

import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class UserController {

    private final UserRepository userRepository;

    @Autowired
    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody RegisterRequest request) {
        // 1. 检查用户名或邮箱是否已存在
        if (userRepository.findByUsername(request.getUsername()).isPresent() ||
                userRepository.findByEmail(request.getEmail()).isPresent()) {
            return new ResponseEntity<>("Username or email already taken.", HttpStatus.BAD_REQUEST);
        }

        // 2. 密码加密 (这里简化为直接存储，实际应用中必须加密!)
        // String encodedPassword = passwordEncoder.encode(request.getPassword());
        String encodedPassword = request.getPassword(); // 临时简化处理

        // 3. 创建并保存新用户
        User newUser = new User(request.getUsername(), encodedPassword, request.getEmail());
        userRepository.save(newUser);

        return new ResponseEntity<>("User registered successfully.", HttpStatus.CREATED);
    }

    @PostMapping("/login") // 登录应该使用 POST 请求
    public ResponseEntity<String> login(@RequestBody LoginRequest request) {
        // 1. 查找用户
        Optional<User> userOptional = userRepository.findByUsername(request.getUsername());

        if (userOptional.isEmpty()) {
            return new ResponseEntity<>("Login failed: Invalid username.", HttpStatus.UNAUTHORIZED);
        }

        User user = userOptional.get();

        // 2. 验证密码 (实际应用中需要用 passwordEncoder.matches(rawPassword, encodedPassword) 验证)
        if (user.getPasswordHashed().equals(request.getPassword())) { // 临时简化处理
            // 3. 登录成功：实际应用中会生成 JWT 或创建 Session
            return new ResponseEntity<>("Login successful! Welcome, " + user.getUsername(), HttpStatus.OK);
        } else {
            return new ResponseEntity<>("Login failed: Invalid password.", HttpStatus.UNAUTHORIZED);
        }
    }

    @GetMapping("/user/me")
    public ResponseEntity<?> getCurrentUserInfo() {

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
            return new ResponseEntity<>("Authenticated user not found.", HttpStatus.NOT_FOUND);
        }

        User user = userOptional.get();
        // 使用 UserResponse DTO 封装数据并返回
        UserResponse userResponse = new UserResponse(user);
        return new ResponseEntity<>(userResponse, HttpStatus.OK);
    }
}
