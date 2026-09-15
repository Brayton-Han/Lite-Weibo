package com.brayton.weibo.admin;

import com.brayton.weibo.entity.Post;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.service.FeedWriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed 缓存回灌工具。
 *
 * feed 采用写扩散：发帖时才把 postId 写进各接收者的 Redis ZSET feed:{userId}，
 * 读路径不回源数据库。因此 Redis 数据丢失后，历史帖子不会再出现在任何 feed 里。
 * 本工具按与实时扇出完全相同的规则（FeedWriteService#resolveFeedTargets）重建索引。
 *
 * 触发方式（一次性，跑完即退出）：
 *   FEED_BACKFILL=true ./mvnw spring-boot:run
 * 或使用根目录脚本： ./start.sh backfill
 *
 * ZSET add 以 (key, member) 为唯一键，重复执行只会覆盖同一 member 的 score，
 * 因此本工具可安全重跑，不会产生重复条目。
 */
@Component
@RequiredArgsConstructor
public class FeedBackfillRunner implements CommandLineRunner {

    private static final int BATCH_SIZE = 200;

    private final PostRepository postRepository;
    private final FeedWriteService feedWriteService;

    @Override
    @Transactional(readOnly = true)
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(System.getenv("FEED_BACKFILL"))) {
            return;
        }

        System.out.println("\n🔄 开始回灌 feed 缓存...\n");
        long start = System.currentTimeMillis();

        int postCount = 0;
        long entryCount = 0;
        int page = 0;

        // Post 实体带 @Where(deleted = false)，软删除的帖子不会被查出来
        Page<Post> posts;
        do {
            posts = postRepository.findAll(
                    PageRequest.of(page, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "id")));

            for (Post post : posts.getContent()) {
                entryCount += feedWriteService.fanOutExistingPost(post);
                postCount++;
            }

            page++;
        } while (posts.hasNext());

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("✅ 回灌完成");
        System.out.println("   帖子数:   " + postCount);
        System.out.println("   feed 条目: " + entryCount);
        System.out.println("   耗时:     " + elapsed + " ms\n");

        System.exit(0);
    }
}
