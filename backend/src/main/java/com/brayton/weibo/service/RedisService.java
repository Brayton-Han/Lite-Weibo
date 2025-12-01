package com.brayton.weibo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redis;

    public void addToFeed(Long userId, Long postId, long timestamp) {
        String key = "feed:" + userId;
        redis.opsForZSet().add(key, postId, timestamp);
    }

    public Set<Object> getFeed(Long userId, int size) {
        String key = "feed:" + userId;
        return redis.opsForZSet().reverseRange(key, 0, size - 1);
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
}
