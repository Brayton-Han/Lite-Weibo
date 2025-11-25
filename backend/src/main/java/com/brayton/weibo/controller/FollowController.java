package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.service.FollowService;
import com.brayton.weibo.service.UserService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
public class FollowController {

    private FollowService followService;
    private UserService userService;

    @GetMapping("user/{id}/following")
    public ResponseEntity<ApiResponse<?>> getFollowingList(@PathVariable long id) {
        List<String> followingList = followService.getFollowings(id);
        return ResponseEntity.ok(ApiResponse.success(followingList));
    }

    @GetMapping("user/{id}/followers")
    public ResponseEntity<ApiResponse<?>> getFollowerList(@PathVariable long id) {
        List<String> followerList = followService.getFollowers(id);
        return ResponseEntity.ok(ApiResponse.success(followerList));
    }

    @PostMapping("/follow/{id}")
    public ResponseEntity<ApiResponse<?>> follow(@AuthenticationPrincipal CustomUserDetails me, @PathVariable long id) {
        followService.follow(me.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Successfully followed"));
    }

    @DeleteMapping("/follow/{id}")
    public ResponseEntity<ApiResponse<?>> unfollow(@AuthenticationPrincipal CustomUserDetails me, @PathVariable long id) {
        followService.unfollow(me.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Successfully unfollowed"));
    }
}
