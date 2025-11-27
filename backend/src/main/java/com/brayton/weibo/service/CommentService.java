package com.brayton.weibo.service;

import com.brayton.weibo.dto.CommentResponse;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.Comment;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.CommentRepository;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final FollowRepository followRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    private CommentResponse buildCommentResponse(Comment comment, Long currentUserId) {

        boolean isFollowed = followRepository.existsByFollowerIdAndFollowingId(currentUserId, comment.getUser().getId());

        return CommentResponse.builder()
                .createdAt(comment.getCreatedAt())
                .content(comment.getContent())
                .id(comment.getId())
                .user(new UserResponse(comment.getUser(), isFollowed))
                .build();
    }

    public List<CommentResponse> getCommentsByPostId(long postId, long uid) {

        List<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtDesc(postId);
        return comments.stream()
                .map(comment -> buildCommentResponse(comment, uid))
                .toList();
    }

    @Transactional
    public void createComment(Long userId, Long postId, String content) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.POST_NOT_FOUND));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.USER_NOT_FOUND));

        Comment comment = Comment.builder()
                .post(post)
                .user(user)
                .content(content)
                .build();

        commentRepository.save(comment);
        postRepository.incrementCommentCount(postId);
    }
}
