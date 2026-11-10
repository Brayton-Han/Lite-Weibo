package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.*;
import com.brayton.weibo.ratelimit.AuthRateLimitPolicies;
import com.brayton.weibo.ratelimit.RateLimiter;
import com.brayton.weibo.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
public class UserController {

    private final UserService userService;
    private final RateLimiter rateLimiter;
    private final AuthRateLimitPolicies authPolicies;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<?>> register(@Valid @RequestBody RegisterRequest request,
                                                   HttpServletRequest http) {
        rateLimiter.check(authPolicies.registerIp(), http.getRemoteAddr());
        userService.register(request);
        return new ResponseEntity<>(ApiResponse.success("User registered successfully."), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest http) {
        // 两个桶都扣：IP 挡撞库，用户名挡多 IP 爆破单个账号。
        // getRemoteAddr 在反向代理后取 X-Forwarded-For 的真实客户端 IP（server.forward-headers-strategy=native）
        rateLimiter.check(authPolicies.loginIp(), http.getRemoteAddr());
        rateLimiter.check(authPolicies.loginUser(), request.getUsername());
        LoginResponse data = userService.login(request);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/user/{id}")
    public ResponseEntity<ApiResponse<?>> getUserById(@AuthenticationPrincipal CustomUserDetails self, @PathVariable long id) {
        UserResponse userResponse = userService.getUserInfoById(id, self.getId());
        return ResponseEntity.ok(ApiResponse.success(userResponse));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<?>> getUsers(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size
    ) {
        List<UserResponse> users = userService.getUsers(lastId, PageLimits.clamp(size));
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @PutMapping("/set")
    public ResponseEntity<ApiResponse<?>> updateUserInfo(@AuthenticationPrincipal CustomUserDetails user, @Valid @RequestBody UpdateUserRequest info) {
        userService.update(user.getId(), info);
        return ResponseEntity.ok(ApiResponse.success("User info updated successfully."));
    }

    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<?>> ping() {
        return ResponseEntity.ok(ApiResponse.success("pong"));
    }
}
