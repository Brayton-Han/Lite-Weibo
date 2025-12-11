package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.LikedPostsResponse;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.service.LikeService;
import com.brayton.weibo.service.PostService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping("/posts/{pid}/like")
    public ResponseEntity<ApiResponse<?>> postLike(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long pid) {
        likeService.likePost(user.getId(), pid);
        return ResponseEntity.ok(ApiResponse.success("Successfully liked post"));
    }

    @DeleteMapping("/posts/{pid}/like")
    public ResponseEntity<ApiResponse<?>> unlikePost(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long pid) {
        likeService.unlikePost(user.getId(), pid);
        return ResponseEntity.ok(ApiResponse.success("Unliked"));
    }
}
