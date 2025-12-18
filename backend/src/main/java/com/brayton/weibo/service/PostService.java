package com.brayton.weibo.service;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.common.FeedRandomizer;
import com.brayton.weibo.common.TimeUtil;
import com.brayton.weibo.dto.*;
import com.brayton.weibo.entity.Like;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostType;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.event.LikeEvent;
import com.brayton.weibo.repository.*;
import com.brayton.weibo.webSocket.WebSocketPusher;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
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
    private final RedisService redisService;
    private final WebSocketPusher wsPusher;

    /**
     * 根据 post 构建完整响应
     */
    private PostResponse buildPostResponse(Post post, Long currentUserId, boolean following, boolean followed) {

        User author = post.getUser();
        boolean isLiked = likeRepository.existsByUserIdAndPostId(currentUserId, post.getId());

        return PostResponse.builder()
                .id(post.getId())
                // Don't care friendCount and postCount
                .user(new UserResponse(author, following, followed, 0, 0))
                .type(post.getType())
                .content(post.getContent())
                .images(post.getImages())
                .refPost(post.getRefPost() == null ? null : buildPostResponse(post.getRefPost(), currentUserId, false, false)) // don't care
                .visibility(post.getVisibility())
                .liked(isLiked)
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .repostCount(post.getRepostCount())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .isEdited(post.isEdited())
                .build();
    }



    // !!! USE FOR NEWEST/FOLLOWING/LIKED POST TIMELINE !!!
    private boolean isVisibleToUser(Post post, boolean self, boolean following, boolean followed) {
        // 自己永远能看到自己的帖子
        if (self) return true;

        // 已取关
        if (!following) return false;

        return switch (post.getVisibility()) {
            case PUBLIC, FOLLOWERS -> true;
            case PRIVATE -> false;
            case FRIENDS -> followed;
        };
    }

    public List<PostResponse> getNewestFeed(Long userId, Long lastTimestamp, int size) {

        long cursor = lastTimestamp == null ? Long.MAX_VALUE : lastTimestamp;
        List<PostResponse> result = new ArrayList<>();

        while (result.size() < size) {
            Set<Object> postIds = redisService.getFeedAfter(userId, cursor, size);
            if (postIds.isEmpty()) break;

            List<Post> posts = postRepository.findByIdInOrderByCreatedAtDesc(postIds);
            for (Post post : posts) {
                if (result.size() >= size) break;

                Long authorId = post.getUser().getId();
                boolean sameUser = authorId.equals(userId);
                boolean following = sameUser || followRepository.existsByFollowerIdAndFollowingId(userId, authorId);
                boolean followed = sameUser || followRepository.existsByFollowerIdAndFollowingId(authorId, userId);

                if (isVisibleToUser(post, sameUser, following, followed)) {
                    result.add(buildPostResponse(post, userId, following, followed));
                }

                cursor = TimeUtil.toTs(post.getCreatedAt());
            }
        }

        return result;
    }

    public List<PostResponse> getFollowingPosts(Long userId) {

        String key = "feed:" + userId;

        // Step 1: Redis 随机抽样
        int size = 20;
        int sampleCount = size * 3;
        List<Long> ids = redisService.getRandomZSetMembers(key, sampleCount);
        if (ids.isEmpty()) return Collections.emptyList();

        // Step 2: DB 批量查
        List<Post> posts = postRepository.findByIdIn(ids);
        if (posts.isEmpty()) return Collections.emptyList();

        // Step 3: 可见性过滤
        List<PostResponse> visiblePosts = new ArrayList<>();
        for (Post p : posts) {
            Long authorId = p.getUser().getId();

            boolean self = authorId.equals(userId);
            if (self) continue;
            boolean following = followRepository.existsByFollowerIdAndFollowingId(userId, authorId);
            boolean followed = followRepository.existsByFollowerIdAndFollowingId(authorId, userId);

            if (isVisibleToUser(p, false, following, followed)) {
                visiblePosts.add(buildPostResponse(p, userId, true, followed));
            }
        }

        if (visiblePosts.isEmpty()) return Collections.emptyList();

        // Step 4: 作者均衡（FeedRandomizer）
        FeedRandomizer randomizer = new FeedRandomizer(
                2,      // 每个作者至少 2 条
                0.3,    // 多出的概率
                0.5     // 衰减因子
        );

        return randomizer.select(
                visiblePosts,
                post -> post.getUser().getId(),
                size
        );
    }

    public LikedPostsResponse getLikedPosts(Long userId, Long lastTimestamp, int size) {

        long cursor = lastTimestamp == null ? Long.MAX_VALUE : lastTimestamp;
        List<PostResponse> result = new ArrayList<>();

        while (result.size() < size) {
            Set<Object> postIds = redisService.getLikedAfter(userId, cursor, size);
            if (postIds.isEmpty()) break;

            List<Post> posts = postRepository.findByIdIn(postIds);
            Map<Long, Post> map = posts.stream()
                    .collect(Collectors.toMap(Post::getId, p -> p));

            List<Post> ordered = postIds.stream()
                    .map(id -> map.get(Long.valueOf(id.toString())))
                    .filter(Objects::nonNull)
                    .toList();

            for (Post post : ordered) {
                if (result.size() >= size) break;

                Like like = likeRepository.findByUserIdAndPostId(userId, post.getId())
                        .orElse(null);
                if (like == null) continue;

                Long authorId = post.getUser().getId();
                boolean sameUser = authorId.equals(userId);
                boolean following = sameUser || followRepository.existsByFollowerIdAndFollowingId(userId, authorId);
                boolean followed = sameUser || followRepository.existsByFollowerIdAndFollowingId(authorId, userId);

                if (isVisibleToUser(post, sameUser, following, followed)) {
                    result.add(buildPostResponse(post, userId, following, followed));
                }

                cursor = TimeUtil.toTs(like.getCreatedAt());
            }
        }

        return new LikedPostsResponse(result, cursor);
    }

    // !!! USER FOR USER_PAGE/FRIENDS POST
    static public List<PostVisibility> visibilityFilter(boolean self, boolean following, boolean followed) {

        if (self)
            return Arrays.asList(PostVisibility.values());

        if (following && followed)
            return Arrays.asList(PostVisibility.PUBLIC, PostVisibility.FOLLOWERS, PostVisibility.FRIENDS);

        if (following)
            return Arrays.asList(PostVisibility.PUBLIC, PostVisibility.FOLLOWERS);

        return List.of(PostVisibility.PUBLIC);
    }

    public List<PostResponse> getAllPosts(Long userId, Long currentUserId, Long lastId, int size) {

        boolean sameUser = userId.equals(currentUserId);
        boolean following = sameUser || followRepository.existsByFollowerIdAndFollowingId(currentUserId, userId);
        boolean followed = sameUser || followRepository.existsByFollowerIdAndFollowingId(userId, currentUserId);

        // 查这个用户的所有帖子
        List<Post> posts = postRepository.findNewestPosts(
                Set.of(userId),
                visibilityFilter(sameUser, following, followed),
                lastId,
                PageRequest.of(0, size)
        );

        return posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, following, followed))
                .toList();
    }

    public List<PostResponse> getFriendPosts(Long currentUserId, Long lastId, int size) {

        List<Post> posts = postRepository.findNewestPosts(
                followRepository.findFriendIds(currentUserId),
                visibilityFilter(false, true, true),
                lastId,
                PageRequest.of(0, size)
        );

        return posts.stream()
                .map(post -> buildPostResponse(post, currentUserId, true, true))
                .toList();
    }

    public List<PostResponse> getSearchResults(String query, Long lastId, int size, Long selfId) {

        lastId = lastId == null ? Long.MAX_VALUE : lastId;

        List<Post> posts;
        boolean containsChinese = ChineseUtil.containsChinese(query);
        if (containsChinese)
            posts = postRepository.searchChinesePosts(
                    "%" + query + "%",
                    lastId,
                    List.of(PostVisibility.PUBLIC),
                    PageRequest.of(0, size)
            );
        else
            posts = postRepository.searchPosts(
                    query,
                    lastId,
                    List.of(PostVisibility.PUBLIC),
                    PageRequest.of(0, size)
            );

        return posts.stream()
                .map(post -> buildPostResponse(post, selfId, false, false))
                .toList();
    }


    @Async
    public void pushPostToFollowersFeed(Post post) {
        Long authorId = post.getUser().getId();
        PostVisibility visibility = post.getVisibility();
        long ts = TimeUtil.toTs(post.getCreatedAt());

        Set<Long> pushIds = new HashSet<>();

        // self
        pushIds.add(authorId);

        if (visibility == PostVisibility.FRIENDS) {
            Set<Long> friendIds = followRepository.findFriendIds(authorId);
            pushIds.addAll(friendIds);
        } else if (visibility == PostVisibility.FOLLOWERS) {
            Set<Long> followerIds = followRepository.findFollowerIds(authorId);
            pushIds.addAll(followerIds);
        } else if (visibility == PostVisibility.PUBLIC) {
            // todo: recommend post
            Set<Long> followerIds = followRepository.findFollowerIds(authorId);
            pushIds.addAll(followerIds);
        }

        for (Long pushId : pushIds) {
            redisService.addToFeed(pushId, post.getId(), ts);
            if (pushId.equals(authorId)) continue;
            wsPusher.notifyUserNewPost(pushId);
        }
    }

    @Transactional
    public PostResponse createPost(Long userId, CreatePostRequest req) {

        // 业务校验：内容和图片不能同时为空
        if (req.getType() == PostType.ORIGINAL &&
                (req.getContent() == null || req.getContent().isBlank())
                && (req.getImages() == null || req.getImages().isEmpty())) {
            throw new WeiboException(CommonErrorCode.POST_CONTENT_NULL);
        }

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.USER_NOT_FOUND));
        Post refPost = (req.getRefPostId() == null) ? null :
                postRepository.findById(req.getRefPostId()).orElseThrow(() -> new WeiboException(CommonErrorCode.POST_NOT_FOUND));

        Post post = new Post();
        post.setUser(author);
        post.setType(req.getType());
        post.setContent(req.getContent());
        post.setImages(req.getImages());
        post.setRefPost(refPost);
        post.setVisibility(req.getVisibility());
        post.setEdited(false);

        Post saved = postRepository.save(post);

        if (refPost != null) {
            postRepository.incrementRepostCount(refPost.getId());
        }

        // fan-out
        pushPostToFollowersFeed(saved);

        return buildPostResponse(saved, userId, true, true); // 返回新帖详情
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

        // 3. 删转发
        if (post.getRefPost() != null) {
            postRepository.decrementRepostCount(post.getRefPost().getId());
        }

        // 3. 删帖子
        postRepository.deleteById(postId);
    }

    @Transactional
    public PostResponse updatePost(long postId, PostUpdateRequest req, Long currentUserId) {

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new WeiboException(CommonErrorCode.POST_NOT_FOUND));

        // 校验权限：只能修改自己的
        if (!post.getUser().getId().equals(currentUserId)) {
            throw new WeiboException(CommonErrorCode.POST_CANT_DELETE);
        }

        PostVisibility oldVisibility = post.getVisibility();
        // 更新可见性（或内容）
        if (req.getVisibility() != null) {
            post.setVisibility(req.getVisibility());
        }
        if (req.getContent() != null) {
            post.setContent(req.getContent());
        }
        if (req.getImages() != null) {
            post.setImages(req.getImages());
        }

        post.setEdited(true);
        Post saved = postRepository.save(post);

        // 🍿 修补 timeline
        if (saved.getVisibility().ordinal() < oldVisibility.ordinal()) {
            pushPostToFollowersFeed(saved);
        }

        return buildPostResponse(saved, currentUserId, true, true);
    }
}