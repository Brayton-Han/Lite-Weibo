package com.brayton.weibo.service;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.common.FeedRandomizer;
import com.brayton.weibo.common.TimeUtil;
import com.brayton.weibo.dto.*;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.entity.Like;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.PostType;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.event.LikeEvent;
import com.brayton.weibo.event.PostEmbeddingEvent;
import com.brayton.weibo.event.PostFanOutEvent;
import com.brayton.weibo.repository.*;
import com.brayton.weibo.webSocket.WebSocketPusher;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
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
    private final ApplicationEventPublisher publisher;
    private final EmbeddingClient embeddingClient;
    private final PostEmbeddingRepository embeddingRepository;
    private final PostLexicalSearchRepository lexicalSearchRepository;

    public static final int SEMANTIC_QUERY_MAX_LENGTH = 200;
    public static final int SEMANTIC_MAX_K = 50;

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

    private PostResponse buildPostResponse(Post post, Long currentUserId, boolean following, boolean followed, boolean isLiked) {

        User author = post.getUser();

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
    /**
     * 帖子的 visibility 设置是否允许 viewer 看到——纯粹的可见性规则，不含任何 feed 语义。
     * 与 SQL 版本的 {@link #visibilityFilter} 保持同一套判定，两者必须一起改。
     */
    private boolean isVisibleToUser(Post post, boolean self, boolean following, boolean followed) {
        // 自己永远能看到自己的帖子
        if (self) return true;

        return switch (post.getVisibility()) {
            case PUBLIC    -> true;              // 公开帖不要求关注关系
            case FOLLOWERS -> following;         // 粉丝可见：viewer 关注了作者
            case FRIENDS   -> following && followed;  // 好友可见：必须互关
            case PRIVATE   -> false;
        };
    }

    /**
     * feed 专用判定：在可见性之上，额外要求 viewer 目前仍然关注作者。
     *
     * feed 采用写扩散，帖子在发布时就被写进了当时各关注者的 ZSet；若之后取关，
     * ZSet 里的残留条目不应再展示，这条规则就是为此而设。
     *
     * liked 列表不经过扇出（条目来自用户自己的点赞行为，与关注关系无关），
     * 因此只走 {@link #isVisibleToUser}，不适用这条额外限制。
     */
    private boolean isVisibleInFeed(Post post, boolean self, boolean following, boolean followed) {
        // 已取关：扇出时写进来的残留条目
        if (!self && !following) return false;

        return isVisibleToUser(post, self, following, followed);
    }

    public List<PostResponse> getNewestFeed(Long userId, Long lastTimestamp, int size) {

        long cursor = lastTimestamp == null ? Long.MAX_VALUE : lastTimestamp;
        List<PostResponse> result = new ArrayList<>();

        while (result.size() < size) {
            int fetch = Math.min(size * 5, 100);
            // 游标由 ZSet score 推进，不能由 DB 查到的帖子推进：若一批全是幽灵条目
            // （帖子已删除），基于帖子的游标原地不动，外层 while 反复取同一窗口 → 死循环。
            List<ZSetOperations.TypedTuple<Object>> entries =
                    redisService.getFeedAfterWithScores(userId, cursor, fetch);
            if (entries.isEmpty()) break;

            List<Long> postIds = entries.stream()
                    .map(ZSetOperations.TypedTuple::getValue)
                    .filter(Objects::nonNull)
                    .map(id -> Long.valueOf(id.toString()))
                    .toList();

            List<Post> posts = postRepository.findByIdInWithUser(postIds);
            Map<Long, Post> postMap =
                    posts.stream().collect(Collectors.toMap(Post::getId, p -> p));

            Set<Long> authorIds = posts.stream()
                    .map(p -> p.getUser().getId())
                    .collect(Collectors.toSet());
            Set<Long> followingIds = followRepository.findFollowingIds(userId, authorIds);
            Set<Long> followedByIds = followRepository.findFollowedByIds(userId, authorIds);

            Set<Long> likedPostIds = likeRepository.findLikedPostIds(userId, postMap.keySet());

            long advanced = cursor;

            for (ZSetOperations.TypedTuple<Object> entry : entries) {
                Object rawId = entry.getValue();
                Double score = entry.getScore();
                if (rawId == null || score == null) continue;

                Long postId = Long.valueOf(rawId.toString());
                Post post = postMap.get(postId);

                if (post == null) {
                    // 幽灵条目：帖子已删除。扇出在事务提交后才执行，
                    // 这里查不到不会是"尚未提交"，可以放心清理。
                    redisService.removeFromFeed(userId, postId);
                } else {
                    Long authorId = post.getUser().getId();
                    boolean sameUser = authorId.equals(userId);
                    boolean following = sameUser || followingIds.contains(authorId);
                    boolean followed  = sameUser || followedByIds.contains(authorId);
                    boolean isLiked = likedPostIds.contains(postId);

                    if (isVisibleInFeed(post, sameUser, following, followed)) {
                        result.add(buildPostResponse(post, userId, following, followed, isLiked));
                    }
                }

                // 幽灵条目同样推进游标，这是不死循环的关键
                advanced = score.longValue();

                if (result.size() >= size) break;
            }

            // 游标必须严格递减，否则下一轮会取到完全相同的窗口
            if (advanced >= cursor) break;
            cursor = advanced;

            // 本批不足 fetch，说明 ZSet 已取尽
            if (entries.size() < fetch) break;
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

            if (isVisibleInFeed(p, false, following, followed)) {
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

    /**
     * 查看 userId 点赞过的帖子列表。
     *
     * 方法内有两个身份，不能混用：
     *  - userId：点赞列表的主人，决定读哪条 liked: ZSet、哪些 Like 记录算数；
     *  - currentUserId：发起请求的人，决定每条帖子是否可见、liked 标记怎么算。
     * 早期版本两处都用 userId，导致 A 能看到 B 点赞过的、本不该对 A 可见的帖子。
     */
    public LikedPostsResponse getLikedPosts(Long userId, Long currentUserId, Long lastTimestamp, int size) {

        long cursor = lastTimestamp == null ? Long.MAX_VALUE : lastTimestamp;
        List<PostResponse> result = new ArrayList<>();

        while (result.size() < size) {
            // 游标必须由 ZSet 的 score 推进，不能由 DB 的 Like.createdAt 推进：
            // liked: ZSet 里可能残留 DB 中已不存在的幽灵条目（取消点赞时未清理、
            // 或帖子被软删除后 findByIdIn 查不到）。若这一批全是幽灵条目，
            // 基于 Like 记录的游标会原地不动，外层 while 反复取同一窗口 → 死循环。
            List<ZSetOperations.TypedTuple<Object>> entries =
                    redisService.getLikedAfterWithScores(userId, cursor, size);
            if (entries.isEmpty()) break;

            Set<Object> postIds = entries.stream()
                    .map(ZSetOperations.TypedTuple::getValue)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            List<Post> posts = postRepository.findByIdIn(postIds);
            Map<Long, Post> map = posts.stream()
                    .collect(Collectors.toMap(Post::getId, p -> p));

            long advanced = cursor;

            for (ZSetOperations.TypedTuple<Object> entry : entries) {
                Object rawId = entry.getValue();
                Double score = entry.getScore();
                if (rawId == null || score == null) continue;

                Long postId = Long.valueOf(rawId.toString());
                Post post = map.get(postId);
                Like like = post == null ? null
                        : likeRepository.findByUserIdAndPostId(userId, postId).orElse(null);

                if (post == null || like == null) {
                    // 幽灵条目：帖子已删除，或已取消点赞但 ZSet 未清理。
                    // 顺手清掉，避免后续分页反复扫到。
                    redisService.removeFromLiked(userId, postId);
                } else {
                    // 可见性一律按请求者（currentUserId）与帖子作者的关系计算，
                    // 而不是按列表主人，否则会越权泄露对请求者不可见的帖子
                    Long authorId = post.getUser().getId();
                    boolean sameUser = authorId.equals(currentUserId);
                    boolean following = sameUser || followRepository.existsByFollowerIdAndFollowingId(currentUserId, authorId);
                    boolean followed = sameUser || followRepository.existsByFollowerIdAndFollowingId(authorId, currentUserId);

                    // 仅当前不可见，点赞关系仍然有效，不做清理
                    if (isVisibleToUser(post, sameUser, following, followed)) {
                        // liked 标记同样按请求者算：这里要回答"我赞过吗"，不是"列表主人赞过吗"
                        result.add(buildPostResponse(post, currentUserId, following, followed));
                    }
                }

                // 这一条已处理完，游标推进到它；幽灵条目同样推进，这是不死循环的关键
                advanced = score.longValue();

                // 装满即停，游标停在最后处理的这一条，下一页从它之后继续，不跳数据
                if (result.size() >= size) break;
            }

            // 游标必须严格递减，否则下一轮会取到完全相同的窗口
            if (advanced >= cursor) break;
            cursor = advanced;

            // 本批不足 size，说明 ZSet 已取尽
            if (entries.size() < size) break;
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

    /**
     * 词面搜索，按相关性排序（RAG Day 10）；原来两路都是「命中即可、按时间排」，没有相关性可言。
     * 含汉字走 trigram 相似度，否则走 ts_rank_cd。游标仍是上一页最后一条的 id，翻页语义见 {@link PostLexicalSearchRepository}。
     */
    public List<PostResponse> getSearchResults(String query, Long lastId, int size, Long selfId) {

        List<Long> ids = (ChineseUtil.containsChinese(query)
                ? lexicalSearchRepository.searchChinese(query, lastId, size)
                : lexicalSearchRepository.searchEnglish(query, lastId, size))
                .stream()
                .map(PostLexicalSearchRepository.Hit::postId)
                .toList();
        Map<Long, Post> posts = postRepository.findByIdInWithUser(ids).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));

        // 按相关性顺序组装；检索与加载之间被删除的帖子由 @Where 滤掉，可见性这里再判一次
        List<PostResponse> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Post post = posts.get(id);
            if (post != null && post.getVisibility() == PostVisibility.PUBLIC) {
                result.add(buildPostResponse(post, selfId, false, false));
            }
        }
        return result;
    }



    /**
     * 纯向量语义搜索（RAG Day 6，临时端点，供 Phase 2 打分前人工感受效果）。
     *
     * 只返回 top-k、不分页：ANN 的结果没有稳定的游标可言，翻页需要按分数做 keyset，
     * 等 Phase 3 混合检索定型后再设计。分数一并返回，便于对比不同查询的结果。
     */
    public List<ScoredPostResponse> getSemanticSearchResults(String query, int k, Long selfId) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > SEMANTIC_QUERY_MAX_LENGTH) {
            throw new WeiboException(CommonErrorCode.SEARCH_QUERY_INVALID);
        }
        k = Math.max(1, Math.min(k, SEMANTIC_MAX_K));

        float[] vector;
        try {
            vector = embeddingClient.embedQuery(q);
        } catch (EmbeddingException e) {
            log.warn("语义搜索查询向量化失败：{}", e.getMessage());
            throw new WeiboException(CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
        }

        List<PostEmbeddingRepository.Hit> hits = embeddingRepository.searchNearest(vector, embeddingClient.modelId(), k);
        Map<Long, Post> posts = postRepository.findByIdInWithUser(
                        hits.stream().map(PostEmbeddingRepository.Hit::postId).toList())
                .stream().collect(Collectors.toMap(Post::getId, p -> p));

        // 按相似度顺序组装。检索与加载之间帖子可能刚被删或改为非公开：@Where 会滤掉已删除的，可见性这里再判一次
        List<ScoredPostResponse> result = new ArrayList<>(hits.size());
        for (PostEmbeddingRepository.Hit hit : hits) {
            Post post = posts.get(hit.postId());
            if (post == null || post.getVisibility() != PostVisibility.PUBLIC) {
                continue;
            }
            result.add(new ScoredPostResponse(buildPostResponse(post, selfId, false, false), hit.score()));
        }
        return result;
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

        // fan-out：事务提交后由 FeedWriteService#onPostFanOut 异步执行
        publisher.publishEvent(new PostFanOutEvent(
                saved.getId(), userId, saved.getVisibility(), TimeUtil.toTs(saved.getCreatedAt())));

        // 向量化：同样在提交后异步执行（EmbeddingWriteService）。只有 PUBLIC 帖子会被搜索到，
        // 其余可见性的内容也没有理由发给第三方 embedding 服务
        if (saved.getVisibility() == PostVisibility.PUBLIC) {
            publisher.publishEvent(new PostEmbeddingEvent(saved.getId()));
        }

        return buildPostResponse(saved, userId, true, true, false); // 返回新帖详情
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

        // 删除向量，并重算「转发并评论」了它的帖子（它们的 chunk_text 里拼着本帖正文）。
        // 软删除不会触发 ON DELETE CASCADE，读时过滤也管不到转发帖，所以必须显式处理
        publisher.publishEvent(new PostEmbeddingEvent(postId));
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
            publisher.publishEvent(new PostFanOutEvent(
                    saved.getId(), currentUserId, saved.getVisibility(), TimeUtil.toTs(saved.getCreatedAt())));
        }

        // 内容或可见性变了都要同步向量（含删除）；没有实质变化时 EmbeddingWriteService 会比对后跳过
        publisher.publishEvent(new PostEmbeddingEvent(saved.getId()));

        return buildPostResponse(saved, currentUserId, true, true);
    }
}