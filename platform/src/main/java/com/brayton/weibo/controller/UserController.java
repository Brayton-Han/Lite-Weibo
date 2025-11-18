package com.brayton.weibo.controller;

import com.brayton.weibo.dto.LoginRequest;
import com.brayton.weibo.dto.RegisterRequest;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<String> register(@RequestBody RegisterRequest request) {
        userService.register(request);
        return new ResponseEntity<>("User registered successfully.", HttpStatus.CREATED);
    }

    @PostMapping("/login") // 登录应该使用 POST 请求
    public ResponseEntity<String> login(@RequestBody LoginRequest request) {
        String message = userService.login(request);
        return ResponseEntity.ok(message);
    }

    @GetMapping("/user/me")
    public ResponseEntity<UserResponse> getCurrentUserInfo() {
        UserResponse userResponse = userService.getCurrentUserInfo();
        return new ResponseEntity<>(userResponse, HttpStatus.OK);
    }

    @GetMapping("user/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable long id) {
        UserResponse userResponse = userService.getUserInfoById(id);
        return new ResponseEntity<>(userResponse, HttpStatus.OK);
    }
}
