package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.*;
import com.brayton.weibo.ratelimit.RateLimitPolicies;
import com.brayton.weibo.ratelimit.RateLimiter;
import com.brayton.weibo.service.AskService;
import com.brayton.weibo.service.PostService;
import com.brayton.weibo.service.RetrievalDetailService;
import com.brayton.weibo.service.SemanticSearchService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
public class PostController {

    private final PostService postService;
    private final AskService askService;
    private final RetrievalDetailService retrievalDetailService;
    private final SemanticSearchService semanticSearchService;
    private final RateLimiter rateLimiter;
    private final RateLimitPolicies rateLimitPolicies;

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

    @GetMapping("/user/{uid}/liked")
    public ResponseEntity<ApiResponse<?>> getLikedPosts(
            @AuthenticationPrincipal CustomUserDetails self,
            @PathVariable long uid,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") int size
    ) {
        LikedPostsResponse posts = postService.getLikedPosts(uid, self.getId(), cursor, size);
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @GetMapping("/posts/search")
    public ResponseEntity<ApiResponse<?>> getSearchPosts(
            @RequestParam() String q,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long lastId,
            @AuthenticationPrincipal CustomUserDetails self
    ) {
        List<PostResponse> posts = postService.getSearchResults(q, lastId, size, self.getId());
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    @GetMapping("/posts/search/semantic")
    public ResponseEntity<ApiResponse<?>> getSemanticSearchPosts(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int k,
            @AuthenticationPrincipal CustomUserDetails self
    ) {
        rateLimiter.check(rateLimitPolicies.search(), self.getId());
        List<ScoredPostResponse> posts = semanticSearchService.search(q, k, self.getId());
        return ResponseEntity.ok(ApiResponse.success(posts));
    }

    /** 检索详情面板：词面 / 向量两路各自的 top-k 与 RRF 融合过程。见 {@link RetrievalDetailService} */
    @GetMapping("/posts/search/retrieval")
    public ResponseEntity<ApiResponse<?>> getRetrievalDetail(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int k,
            @AuthenticationPrincipal CustomUserDetails self
    ) {
        rateLimiter.check(rateLimitPolicies.search(), self.getId());
        return ResponseEntity.ok(ApiResponse.success(retrievalDetailService.explain(q, k, self.getId())));
    }

    /**
     * 站内问答：按意图分流，经验类带引用作答，共鸣类返回搜索结果，风险类返回求助渠道。见 {@link AskService}。
     * 限流在 AskService 里做：被限后降级成 DEGRADED，不是 429
     */
    @GetMapping("/posts/search/ask")
    public ResponseEntity<ApiResponse<?>> ask(
            @RequestParam String q,
            @AuthenticationPrincipal CustomUserDetails self
    ) {
        return ResponseEntity.ok(ApiResponse.success(askService.ask(q, self.getId())));
    }
}
