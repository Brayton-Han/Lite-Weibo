package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.*;
import com.brayton.weibo.service.UserService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<?>> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return new ResponseEntity<>(ApiResponse.success("User registered successfully."), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse data = userService.login(request);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("user/{id}")
    public ResponseEntity<ApiResponse<?>> getUserById(@AuthenticationPrincipal CustomUserDetails self, @PathVariable long id) {
        UserResponse userResponse = userService.getUserInfoById(id, self.getId());
        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }

    @PutMapping("/set")
    public ResponseEntity<ApiResponse<?>> updateUserInfo(@AuthenticationPrincipal CustomUserDetails user, @RequestBody UserResponse info) {
        userService.update(user.getId(), info);
        return ResponseEntity.ok(ApiResponse.success("User info updated successfully."));
    }

    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<?>> ping() {
        return ResponseEntity.ok(ApiResponse.success("pong"));
    }
}
