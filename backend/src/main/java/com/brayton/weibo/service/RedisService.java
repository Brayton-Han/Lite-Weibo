package com.brayton.weibo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redis;

    /* feed operations */
    public void addToFeed(Long userId, Long postId, long timestamp) {
        String key = "feed:" + userId;
        redis.opsForZSet().add(key, postId, timestamp);
    }

    public void removeFromFeed(Long userId, Long postId) {
        String key = "feed:" + userId;
        redis.opsForZSet().remove(key, postId);
    }

    public void trimFeed(long userId, int maxSize) {
        String key = "feed:" + userId;
        redis.opsForZSet().removeRange(key, 0, -maxSize - 1);
    }

    /* outbox operations（大 V 拉模式，见 FeedWriteService#usePullMode） */
    static String outboxKey(Long authorId) {
        return "outbox:" + authorId;
    }

    public void addToOutbox(Long authorId, Long postId, long timestamp) {
        redis.opsForZSet().add(outboxKey(authorId), postId, timestamp);
    }

    public void trimOutbox(long authorId, int maxSize) {
        redis.opsForZSet().removeRange(outboxKey(authorId), 0, -maxSize - 1);
    }

    public void removeFromOutbox(Long authorId, Long postId) {
        redis.opsForZSet().remove(outboxKey(authorId), postId);
    }

    /**
     * 推拉合并后的一条 feed 条目。outboxAuthorId 为 null 表示来自用户自己的收件箱 feed:，
     * 否则来自该作者的发件箱——清理幽灵条目时要删对地方。
     */
    public record FeedEntry(Long postId, long score, Long outboxAuthorId) {
    }

    /**
     * 收件箱 feed:{userId} 与 pullAuthorIds 各自的发件箱，各取 score < lastTimestamp 的前 size 条，
     * 按 score 倒序归并、按 postId 去重后取前 size 条。
     *
     * 每个源都取了自己的前 size 条，所以归并结果就是并集的前 size 条；归并结果不足 size
     * 说明所有源都已取尽，调用方据此判断结束。去重是因为同一帖子可能两边都有
     * （关注时预热会把大 V 的近期帖灌进收件箱）。一次管道往返取完所有源。
     */
    public List<FeedEntry> getMergedFeedAfter(Long userId, Collection<Long> pullAuthorIds,
                                              long lastTimestamp, int size) {
        List<Long> sources = new ArrayList<>();
        sources.add(null);
        sources.addAll(pullAuthorIds);

        List<Object> results = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                for (Long authorId : sources) {
                    String key = authorId == null ? "feed:" + userId : outboxKey(authorId);
                    operations.opsForZSet().reverseRangeByScoreWithScores(
                            key, Double.NEGATIVE_INFINITY, lastTimestamp - 1, 0, size);
                }
                return null;
            }
        });

        List<FeedEntry> all = new ArrayList<>();
        for (int i = 0; i < sources.size() && i < results.size(); i++) {
            if (!(results.get(i) instanceof Collection<?> tuples)) continue;
            for (Object o : tuples) {
                if (!(o instanceof ZSetOperations.TypedTuple<?> t) || t.getValue() == null || t.getScore() == null) {
                    continue;
                }
                all.add(new FeedEntry(Long.valueOf(t.getValue().toString()), t.getScore().longValue(), sources.get(i)));
            }
        }

        // 同分时按 postId 倒序，保证同一窗口每次归并顺序一致
        all.sort(Comparator.comparingLong(FeedEntry::score).thenComparingLong(FeedEntry::postId).reversed());
        Set<Long> seen = new HashSet<>();
        List<FeedEntry> merged = new ArrayList<>(size);
        for (FeedEntry e : all) {
            if (merged.size() >= size) break;
            if (seen.add(e.postId())) merged.add(e);
        }
        return merged;
    }

    /* like operations */
    public void addToLiked(Long userId, Long postId, long timestamp) {
        String key = "liked:" + userId;
        redis.opsForZSet().add(key, postId, timestamp);
    }

    /**
     * 按 score 倒序取 liked: ZSet 的一页，连同 score（点赞时间戳）一起返回。
     * 分页游标必须由 score 推进：ZSet 中可能残留 DB 里已不存在的幽灵条目，
     * 只有 score 能保证游标在这些条目上也继续前进，不会卡在同一窗口。
     */
    public List<ZSetOperations.TypedTuple<Object>> getLikedAfterWithScores(
            Long userId, long lastTimestamp, int size) {
        String key = "liked:" + userId;

        Set<ZSetOperations.TypedTuple<Object>> raw =
                redis.opsForZSet().reverseRangeByScoreWithScores(
                        key,
                        Double.NEGATIVE_INFINITY,   // min
                        lastTimestamp - 1,          // max
                        0,
                        size
                );

        return raw == null ? Collections.emptyList() : new ArrayList<>(raw);
    }

    public void removeFromLiked(Long userId, Long postId) {
        String key = "liked:" + userId;
        redis.opsForZSet().remove(key, postId);
    }

    /**
     * 从若干 ZSet 的并集里均匀随机抽 sampleCount 个成员（可重复）：
     * 每次在总长度上取一个全局下标，再映射到对应 key 的局部下标，条目多的 key 被抽中的概率按比例更高。
     */
    public List<Long> getRandomZSetMembers(List<String> keys, int sampleCount) {
        long[] sizes = new long[keys.size()];
        long total = 0;
        for (int k = 0; k < keys.size(); k++) {
            Long card = redis.opsForZSet().zCard(keys.get(k));
            sizes[k] = card == null ? 0 : card;
            total += sizes[k];
        }
        if (total == 0) return Collections.emptyList();

        ThreadLocalRandom rand = ThreadLocalRandom.current();
        List<Long> result = new ArrayList<>(sampleCount);

        for (int i = 0; i < sampleCount; i++) {
            long index = rand.nextLong(0, total);
            int k = 0;
            while (index >= sizes[k]) {
                index -= sizes[k];
                k++;
            }
            Set<Object> idSet = redis.opsForZSet().range(keys.get(k), index, index);
            if (idSet != null && !idSet.isEmpty()) {
                Object raw = idSet.iterator().next();
                result.add(Long.valueOf(raw.toString()));
            }
        }

        return result;
    }
}
