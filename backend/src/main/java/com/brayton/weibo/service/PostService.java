package com.brayton.weibo.service;

import com.brayton.weibo.dto.CreatePostRequest;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.Like;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.ErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.FollowRepository;
import com.brayton.weibo.repository.LikeRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final LikeRepository likeRepository;

    /**
     * 根据 post 构建完整响应
     */
    private PostResponse buildPostResponse(Post post, Long currentUserId) {
        User author = post.getUser();
        boolean isLiked = likeRepository.existsByUserIdAndPostId(currentUserId, post.getId());
        boolean isFollowed = followRepository.existsByFollowerIdAndFollowingId(currentUserId, author.getId());

        return PostResponse.builder()
                .id(post.getId())
                .user(new UserResponse(author, isFollowed))
                .content(post.getContent())
                .images(post.getImages())
                .visibility(post.getVisibility())
                .liked(isLiked)
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }

    @Transactional
    public PostResponse createPost(Long userId, CreatePostRequest req) {
        // 业务校验：内容和图片不能同时为空
        if ((req.getContent() == null || req.getContent().isBlank())
                && (req.getImages() == null || req.getImages().isEmpty())) {
            throw new WeiboException(CommonErrorCode.POST_CONTENT_NULL);
        }
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.USER_NOT_FOUND));

        Post post = new Post();
        post.setUser(author);
        post.setContent(req.getContent());
        post.setImages(req.getImages());
        post.setVisibility(req.getVisibility());

        Post saved = postRepository.save(post);

        return buildPostResponse(saved, userId); // 返回新帖详情
    }

    public List<PostResponse> getAllPosts(Long userId, Long currentUserId) {

        // 查这个用户的所有帖子
        List<Post> posts = postRepository.findAllByUserIdOrderByCreatedAtDesc(userId);

        return posts.stream()
                .map(post -> buildPostResponse(post, currentUserId))
                .toList();
    }

    @Transactional
    public void likePost(Long userId, Long postId) {

        // 避免重复点赞
        if (likeRepository.existsByUserIdAndPostId(userId, postId)) return;

        // 保存 Like 记录
        Like like = new Like();
        like.setUserId(userId);
        like.setPostId(postId);
        likeRepository.save(like);

        // 更新 Post 的 likeCount
        postRepository.incrementLikeCount(postId);
    }

    @Transactional
    public void unlikePost(Long userId, Long postId) {

        if (!likeRepository.existsByUserIdAndPostId(userId, postId)) return;

        likeRepository.deleteByUserIdAndPostId(userId, postId);

        // 更新 Post 的 likeCount
        postRepository.decrementLikeCount(postId);
    }

    @Transactional
    public void deletePost(Long userId, Long postId) {

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.POST_NOT_FOUND));

        // 校验权限：只能删除自己的
        if (!post.getUser().getId().equals(userId)) {
            throw new WeiboException(CommonErrorCode.POST_CANT_DELETE);
        }

        // 1. 删点赞
        likeRepository.deleteAllByPostId(postId);

        // 2. 删评论（如果有 commentRepository）
        //commentRepository.deleteAllByPostId(postId);

        // 3. 删帖子
        postRepository.deleteById(postId);
    }

}