package com.brayton.weibo.test;

import lombok.Data;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comment Queue 消费端性能监控
 */
@Component
@Data
public class CommentQueueMetrics {
    
    private AtomicInteger totalConsumed = new AtomicInteger(0);
    private AtomicInteger totalErrors = new AtomicInteger(0);
    private long testStartTime = System.currentTimeMillis();
    private List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    
    public void recordLatency(long latencyMs) {
        latencies.add(latencyMs);
        totalConsumed.incrementAndGet();
    }
    
    public void recordError() {
        totalErrors.incrementAndGet();
    }
    
    public void printMetrics() {
        long duration = System.currentTimeMillis() - testStartTime;
        int consumed = totalConsumed.get();
        double throughput = consumed > 0 ? (1000.0 * consumed / duration) : 0;
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📊 Comment Queue 消费性能统计");
        System.out.println("=".repeat(60));
        System.out.println("测试时长:        " + String.format("%.2f", duration / 1000.0) + " 秒");
        System.out.println("消费消息数:      " + consumed + " 条");
        System.out.println("消费错误:        " + totalErrors.get() + " 条");
        System.out.println("实际吞吐量:      " + String.format("%.2f", throughput) + " msg/sec");
        
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            long minLatency = Collections.min(latencies);
            long maxLatency = Collections.max(latencies);
            long p50 = latencies.get(latencies.size() / 2);
            long p95 = latencies.get((int)(latencies.size() * 0.95));
            long p99 = latencies.get(Math.min((int)(latencies.size() * 0.99), latencies.size() - 1));
            
            System.out.println("\n延迟统计 (毫秒):");
            System.out.println("  平均:          " + String.format("%.2f", avgLatency) + " ms");
            System.out.println("  最小:          " + minLatency + " ms");
            System.out.println("  最大:          " + maxLatency + " ms");
            System.out.println("  P50 (中位数):  " + p50 + " ms");
            System.out.println("  P95:           " + p95 + " ms");
            System.out.println("  P99:           " + p99 + " ms");
        }
        
        System.out.println("=".repeat(60) + "\n");
    }
}
