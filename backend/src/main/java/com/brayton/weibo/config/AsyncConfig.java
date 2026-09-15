package com.brayton.weibo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行配置。
 *
 * 此前项目中三处 {@code @Async} 全部失效：既缺 {@code @EnableAsync}，
 * 又都是类内自调用绕过了代理，所谓"异步优化"实际全是同步执行。
 *
 * 这里显式声明有界线程池，而不依赖 Spring Boot 默认的 applicationTaskExecutor
 * ——后者队列无界，积压时会一路吃内存直到 OOM，没有背压。
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    /** feed 写入类异步任务的线程池 bean 名，@Async 需显式引用，否则会落到默认执行器 */
    public static final String FEED_EXECUTOR = "feedTaskExecutor";

    @Bean(FEED_EXECUTOR)
    public ThreadPoolTaskExecutor feedTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("feed-async-");

        // 队列满时退化为调用线程执行：feed 扇出丢失会造成 Redis 与 DB 不一致，
        // 宁可让发帖请求变慢（天然背压），也不丢任务。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 关闭时等待在途任务跑完，避免重启丢扇出
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * void 返回的 @Async 方法抛出的异常不会传播给调用方，没有这个处理器就是彻底静默。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("异步任务执行失败: {}#{} 参数={}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        Arrays.toString(params),
                        ex);
    }
}
