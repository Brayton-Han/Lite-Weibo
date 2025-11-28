package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.CreatePostRequest;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.service.PostService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Controller
@AllArgsConstructor
public class PostController {

    private final PostService postService;

    @GetMapping("/user/{uid}/posts")
    public ResponseEntity<ApiResponse<?>> getPosts(@AuthenticationPrincipal CustomUserDetails self, @PathVariable long uid) {
        List<PostResponse> posts = postService.getAllPosts(uid, self.getId());
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @DeleteMapping("/posts/{pid}")
    public ResponseEntity<ApiResponse<?>> deletePosts(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long pid) {
        postService.deletePost(user.getId(), pid);
        return ResponseEntity.ok(ApiResponse.success("Successfully deleted post"));
    }

    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<?>> getNewestFeed(@AuthenticationPrincipal CustomUserDetails self) {
        List<PostResponse> posts = postService.getNewestFeed(self.getId());
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @GetMapping("/posts/friends")
    public ResponseEntity<ApiResponse<?>> getFriendPosts(@AuthenticationPrincipal CustomUserDetails self) {
        List<PostResponse> posts = postService.getFriendPosts(self.getId());
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @PostMapping("/posts")
    public ResponseEntity<ApiResponse<?>> post(@AuthenticationPrincipal CustomUserDetails user, @RequestBody CreatePostRequest post) {
        PostResponse newPost = postService.createPost(user.getId(), post);
        return ResponseEntity.ok(ApiResponse.success(newPost));
    }

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
}
