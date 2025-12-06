package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.LikedPostsResponse;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.service.PostService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
public class LikeController {

    private final PostService postService;

    @PostMapping("/posts/{pid}/like")
    public ResponseEntity<ApiResponse<?>> postLike(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long pid) {
        postService.likePost(user.getId(), pid);
        return ResponseEntity.ok(ApiResponse.success("Successfully liked post"));
    }

    @DeleteMapping("/posts/{pid}/like")
    public ResponseEntity<ApiResponse<?>> unlikePost(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long pid) {
        postService.unlikePost(user.getId(), pid);
        return ResponseEntity.ok(ApiResponse.success("Unliked"));
    }

    @GetMapping("/user/{uid}/liked")
    public ResponseEntity<ApiResponse<?>> getLikedPosts(
            @PathVariable long uid,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") int size
    ) {
        LikedPostsResponse posts = postService.getLikedPosts(uid, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(posts));
    }
}
