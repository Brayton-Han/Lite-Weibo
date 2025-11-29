package com.brayton.weibo.controller;

import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.ApiResponse;
import com.brayton.weibo.dto.CommentRequest;
import com.brayton.weibo.dto.CommentResponse;
import com.brayton.weibo.service.CommentService;
import com.brayton.weibo.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<?>> getComments(@AuthenticationPrincipal CustomUserDetails self, @PathVariable Long postId) {
        List<CommentResponse> comments = commentService.getCommentsByPostId(postId, self.getId());
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<?>> createComment(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long postId,
            @RequestBody @Valid CommentRequest request) {
        // 直接使用 get 方法，安全且清晰
        String content = request.getContent();

        // 调用 Service
        commentService.createComment(user.getId(), postId, content);

        return ResponseEntity.ok(ApiResponse.success("Comment published"));
    }

    @DeleteMapping("/comments/{cid}")
    public ResponseEntity<ApiResponse<?>> deleteComment(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long cid
    ) {
        commentService.deleteComment(cid, user.getId());
        return ResponseEntity.ok(ApiResponse.success("Comment deleted"));
    }
}
