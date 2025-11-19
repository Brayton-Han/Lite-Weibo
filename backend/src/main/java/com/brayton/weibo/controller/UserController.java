package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<?>> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return new ResponseEntity<>(ApiResponse.success("User registered successfully."), HttpStatus.CREATED);
    }

    @PostMapping("/login") // 登录应该使用 POST 请求
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request) {
        String message = userService.login(request);
        return ResponseEntity.ok(ApiResponse.success(message));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<?>> getCurrentUserInfo(@AuthenticationPrincipal CustomUserDetails user) {
        UserResponse userResponse = userService.getUserInfoById(user.getId());
        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }

    @GetMapping("user/{id}")
    public ResponseEntity<ApiResponse<?>> getUserById(@PathVariable long id) {
        UserResponse userResponse = userService.getUserInfoById(id);
        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }

    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<?>> ping() {
        return ResponseEntity.ok(ApiResponse.success("pong"));
    }
}
