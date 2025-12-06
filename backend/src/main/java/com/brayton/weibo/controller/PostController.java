package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.CreatePostRequest;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.PostUpdateRequest;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.service.FileService;
import com.brayton.weibo.service.PostService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.ReactiveUserDetailsPasswordService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@AllArgsConstructor
public class PostController {

    private final PostService postService;

    @GetMapping("/user/{uid}/posts")
    public ResponseEntity<ApiResponse<?>> getPosts(
            @AuthenticationPrincipal CustomUserDetails self,
            @PathVariable long uid,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size
            ) {
        List<PostResponse> posts = postService.getAllPosts(uid, self.getId(), lastId, size);
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @PutMapping("/posts/{id}")
    public ResponseEntity<ApiResponse<?>> updatePost(
            @PathVariable long id,
            @RequestBody PostUpdateRequest req,
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        PostResponse res = postService.updatePost(id, req, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(res));
    }


    @DeleteMapping("/posts/{pid}")
    public ResponseEntity<ApiResponse<?>> deletePosts(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long pid) {
        postService.deletePost(user.getId(), pid);
        return ResponseEntity.ok(ApiResponse.success("Successfully deleted post"));
    }

    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<?>> getNewestFeed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CustomUserDetails self
    ) {
        List<PostResponse> posts = postService.getNewestFeed(self.getId(), cursor, size);
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @GetMapping("/posts/following")
    public ResponseEntity<ApiResponse<?>> getFollowingPosts(@AuthenticationPrincipal CustomUserDetails self) {
        List<PostResponse> posts = postService.getFollowingPosts(self.getId());
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @GetMapping("/posts/friends")
    public ResponseEntity<ApiResponse<?>> getFriendPosts(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CustomUserDetails self
    ) {
        List<PostResponse> posts = postService.getFriendPosts(self.getId(), lastId, size);
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @PostMapping("/posts")
    public ResponseEntity<ApiResponse<?>> createPost(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestBody @Valid CreatePostRequest request
    ) {
        PostResponse newPost = postService.createPost(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(newPost));
    }
}
