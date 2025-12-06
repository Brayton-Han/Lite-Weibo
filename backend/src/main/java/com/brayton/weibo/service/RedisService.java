package com.brayton.weibo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    public Set<Object> getFeedAfter(Long userId, long lastTimestamp, int size) {
        String key = "feed:" + userId;

        return redis.opsForZSet().reverseRangeByScore(
                key,
                Double.NEGATIVE_INFINITY,   // min
                lastTimestamp - 1,          // max
                0,
                size
        );
    }

    public void trimFeed(long userId, int maxSize) {
        String key = "feed:" + userId;
        redis.opsForZSet().removeRange(key, 0, -maxSize - 1);
    }

    /* like operations */
    public void addToLiked(Long userId, Long postId, long timestamp) {
        String key = "liked:" + userId;
        redis.opsForZSet().add(key, postId, timestamp);
    }

    public Set<Object> getLikedAfter(Long userId, long lastTimestamp, int size) {
        String key = "liked:" + userId;

        return redis.opsForZSet().reverseRangeByScore(
                key,
                Double.NEGATIVE_INFINITY,   // min
                lastTimestamp - 1,          // max
                0,
                size
        );
    }

    public List<Long> getRandomZSetMembers(String key, int sampleCount) {
        long total = redis.opsForZSet().zCard(key);
        if (total == 0) return Collections.emptyList();

        ThreadLocalRandom rand = ThreadLocalRandom.current();
        List<Long> result = new ArrayList<>(sampleCount);

        for (int i = 0; i < sampleCount; i++) {
            long index = rand.nextLong(0, total);
            Set<Object> idSet = redis.opsForZSet().range(key, index, index);
            if (idSet != null && !idSet.isEmpty()) {
                Object raw = idSet.iterator().next();
                result.add(Long.valueOf(raw.toString()));
            }
        }

        return result;
    }
}
