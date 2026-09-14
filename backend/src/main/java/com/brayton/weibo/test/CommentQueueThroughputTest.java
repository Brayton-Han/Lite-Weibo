package com.brayton.weibo.test;

import com.brayton.weibo.config.RabbitConfig;
import com.brayton.weibo.event.CommentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class CommentQueueThroughputTest implements CommandLineRunner {
    
    private final RabbitTemplate rabbitTemplate;
    private final CommentQueueMetrics metrics;
    
    // 通过环境变量控制
    private static final int MESSAGES_PER_SECOND = 
        Integer.parseInt(System.getenv().getOrDefault("THROUGHPUT", "1000"));
    private static final int DURATION_SECONDS = 
        Integer.parseInt(System.getenv().getOrDefault("DURATION", "30"));
    
    @Override
    public void run(String... args) throws Exception {
        String testMode = System.getenv("TEST_MODE");
        if (!"comment-throughput".equals(testMode)) {
            return;
        }
        
        System.out.println("\n🚀 开始 Comment Queue 吞吐量测试...\n");
        System.out.println("配置参数: ");
        System.out.println("  吞吐量: " + MESSAGES_PER_SECOND + " msg/sec");
        System.out.println("  时长: " + DURATION_SECONDS + " 秒\n");
        
        runThroughputTest();
        
        // 等待消费者处理所有消息
        System.out.println("⏳ 等待消费者处理消息... (10秒)");
        Thread.sleep(10000);
        
        // 输出消费端统计
        metrics.printMetrics();
        
        System.exit(0);
    }
    
    private void runThroughputTest() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        int interval = 1000 / MESSAGES_PER_SECOND;
        AtomicInteger messageCount = new AtomicInteger(0);
        
        System.out.println("📤 发送消息中... (目标: " + MESSAGES_PER_SECOND + " msg/sec)");
        
        long endTime = startTime + (DURATION_SECONDS * 1000);
        while (System.currentTimeMillis() < endTime) {
            // 发送时间由 Event 基类构造时自动记录 (Event#timestamp)
            CommentEvent event = new CommentEvent(
                1L,                 // fromUserId
                2L,                 // toUserId  
                100L,               // postId
                "Test comment #" + messageCount.get()
            );
            
            try {
                rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE,
                    "notification.comment",
                    event
                );
                messageCount.incrementAndGet();
            } catch (Exception e) {
                System.err.println("❌ 发送失败: " + e.getMessage());
            }
            
            if (messageCount.get() % 500 == 0 && messageCount.get() > 0) {
                System.out.println("  已发送: " + messageCount.get() + " 条消息");
            }
            
            Thread.sleep(interval);
        }
        
        long actualDuration = System.currentTimeMillis() - startTime;
        printProducerStats(messageCount.get(), actualDuration);
    }
    
    private void printProducerStats(int messageCount, long durationMs) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📊 Comment Queue 生产端测试结果");
        System.out.println("=".repeat(60));
        System.out.println("测试时长:        " + String.format("%.2f", durationMs / 1000.0) + " 秒");
        System.out.println("发送消息数:      " + messageCount + " 条");
        System.out.println("实际吞吐量:      " + 
            String.format("%.2f", 1000.0 * messageCount / durationMs) + " msg/sec");
        System.out.println("=".repeat(60) + "\n");
    }
}
