package com.brayton.weibo.service;

import com.brayton.weibo.dto.CreatePostRequest;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.Like;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.ErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final LikeRepository likeRepository;
    private final CommentRepository commentRepository;

    /**
     * 根据 post 构建完整响应
     */
    private PostResponse buildPostResponse(Post post, Long currentUserId, boolean following, boolean followed) {

        User author = post.getUser();
        boolean isLiked = likeRepository.existsByUserIdAndPostId(currentUserId, post.getId());

        return PostResponse.builder()
                .id(post.getId())
                .user(new UserResponse(author, following, followed, 0)) // Don't care friendCount
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

        return buildPostResponse(saved, userId, true, true); // 返回新帖详情
    }

    private List<PostVisibility> visibilityFilter(boolean self, boolean following, boolean followed) {

        if (self)
            return Arrays.asList(PostVisibility.values());

        if (following && followed)
            return Arrays.asList(PostVisibility.PUBLIC, PostVisibility.FOLLOWERS, PostVisibility.FRIENDS);

        if (following)
            return Arrays.asList(PostVisibility.PUBLIC, PostVisibility.FOLLOWERS);

        return List.of(PostVisibility.PUBLIC);
    }

    public List<PostResponse> getAllPosts(Long userId, Long currentUserId) {

        boolean following = followRepository.existsByFollowerIdAndFollowingId(currentUserId, userId);
        boolean followed = followRepository.existsByFollowerIdAndFollowingId(userId, currentUserId);

        List<PostVisibility> visibilities = visibilityFilter(userId.equals(currentUserId), following, followed);

        // 查这个用户的所有帖子
        List<Post> posts = postRepository.findByUserIdAndVisibilityInOrderByCreatedAtDesc(userId, visibilities);

        return posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, following, followed))
                .toList();
    }

    public List<PostResponse> getNewestFeed(Long currentUserId) {

        List<PostResponse> postResponses = new ArrayList<>();

        // self
        List<Post> posts = postRepository.findByUserIdAndVisibilityIn(
                currentUserId,
                visibilityFilter(true, true, true)
        );
        postResponses.addAll(posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, true, true))
                .toList()
        );

        // friends
        Set<Long> friendIds = followRepository.findFriendIds(currentUserId);
        posts = postRepository.findByUserIdInAndVisibilityIn(
                friendIds,
                visibilityFilter(false, true, true)
        );
        postResponses.addAll(posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, true, true))
                .toList()
        );

        // following
        Set<Long> followingIds = followRepository.findFollowingIds(currentUserId);
        Set<Long> oneWayFollowing = followingIds.stream()
                .filter(id -> !friendIds.contains(id))
                .collect(Collectors.toSet());
        posts = postRepository.findByUserIdInAndVisibilityIn(
                oneWayFollowing,
                visibilityFilter(false, true, false)
        );
        postResponses.addAll(posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, true, false))
                .toList()
        );

        postResponses.sort(Comparator.comparing(PostResponse::getCreatedAt).reversed());

        // 转成 DTO
        return postResponses;
    }

    public List<PostResponse> getFriendPosts(Long currentUserId) {

        Set<Long> friendIds = followRepository.findFriendIds(currentUserId);
        List<PostVisibility> visibilities = visibilityFilter(false, true, true);

        List<Post> posts = postRepository.findByUserIdInAndVisibilityInOrderByCreatedAtDesc(friendIds, visibilities);

        return posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, true, true))
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
        commentRepository.deleteAllByPostId(postId);

        // 3. 删帖子
        postRepository.deleteById(postId);
    }

}