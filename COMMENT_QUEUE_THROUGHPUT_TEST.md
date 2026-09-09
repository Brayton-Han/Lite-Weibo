# Comment 消息队列吞吐量测试指南

## 📋 概述

本文档提供三种方式来测试 Lite-Weibo 项目中 comment 消息队列的吞吐量和延迟性能。

---

## 方式1️⃣ : Python Pika 脚本（快速验证，推荐）

### 适用场景
- 快速验证队列性能
- 无需启动完整的 Spring 应用
- 轻量级测试

### 使用步骤

#### 1. 确保依赖已安装
```bash
pip install pika
```

#### 2. 运行改进的测试脚本
```bash
cd pika
python test_comment_throughput.py
```

#### 3. 调整参数（可选）
在 `test_comment_throughput.py` 中修改：
```python
MESSAGES_PER_SECOND = 500  # 改为需要的吞吐量
DURATION_SECONDS = 30      # 改为需要的测试时长
```

### 预期输出
```
[14:23:45] 开始生产 comment 消息...
[14:23:45] 开始消费 comment 消息...
已消费 100 条消息，当前延迟: 1.23 ms
已消费 200 条消息，当前延迟: 1.45 ms
...
[14:24:15] 生产完毕，共发送 15000 条消息

============================================================
📊 Comment Queue 吞吐量测试结果
============================================================
测试时长:        30.05 秒
发送消息数:      15000 条
消费消息数:      15000 条
实际吞吐量:      499.17 msg/sec

延迟统计 (毫秒):
  平均:          1.23 ms
  最小:          0.45 ms
  最大:          5.67 ms
  P95:           2.34 ms
  P99:           3.45 ms
============================================================
```

---

## 方式2️⃣ : Spring Boot 应用内压测（精准度高，推荐）

### 适用场景
- 在完整的 Spring Boot 应用环境中测试
- 更准确的性能数据
- 结合数据库、WebSocket 等其他组件模拟真实场景

### 使用步骤

#### 1. 启动所有必要服务
```bash
docker-compose up -d
```

确保 RabbitMQ、PostgreSQL、Redis 都已启动：
```bash
docker ps | grep weibo
```

#### 2. 运行压测

**方式A：启动完整应用进行压测**
```bash
cd backend
TEST_MODE=comment-throughput mvn spring-boot:run
```

**方式B：通过 Maven 编译后运行**
```bash
cd backend
mvn clean compile
TEST_MODE=comment-throughput mvn spring-boot:run
```

#### 3. 查看结果

测试完成后，控制台会输出：
```
🚀 开始 Comment Queue 吞吐量测试...

📤 发送消息中... (目标: 1000 msg/sec)
  已发送: 100 条消息
  已发送: 200 条消息
  ...
  已发送: 30000 条消息

✅ 发送完毕!

============================================================
📊 Comment Queue 吞吐量测试结果
============================================================
测试时长:        30.00 秒
发送消息数:      30000 条
实际吞吐量:      1000.00 msg/sec
============================================================

⏳ 等待消费者处理消息... (约5秒后显示消费统计)
```

---

## 方式3️⃣ : RabbitMQ PerfTest 工具（官方工具，最专业）

### 安装

#### 1. 下载 PerfTest
```bash
# macOS
brew install rabbitmq-perf-test

# 或手动下载
wget https://github.com/rabbitmq/rabbitmq-perf-test/releases/download/v2.18.0/rabbitmq-perf-test-2.18.0-bin.tar.gz
tar xzf rabbitmq-perf-test-2.18.0-bin.tar.gz
```

#### 2. 运行压测

**生产者压测：**
```bash
bin/runjava com.rabbitmq.perf.PerfTest \
  --exchange notification.exchange \
  --routing-key notification.comment \
  --rate 500 \
  --queues 1 \
  --queue-args 'x-max-length=100000' \
  --messages 15000
```

**消费者压测：**
```bash
bin/runjava com.rabbitmq.perf.Consumer \
  --queue notification.comment.queue \
  --messages 15000
```

**双向压测（同时生产和消费）：**
```bash
bin/runjava com.rabbitmq.perf.PerfTest \
  --exchange notification.exchange \
  --routing-key notification.comment \
  --rate 500 \
  --queues 1 \
  --messages 15000 \
  --time 60
```

### 预期输出
```
         Rate (msg/s) | Latency (ms)
    prod: 500.12      | min: 0.23, avg: 1.45, max: 12.34
    cons: 500.08      | min: 0.12, avg: 1.23, max: 10.56
```

---

## 📊 性能基准参考

| 指标 | 基准值 | 说明 |
|------|--------|------|
| 吞吐量 | 500+ msg/sec | 单个消费者 |
| 平均延迟 | < 2 ms | 从发送到消费 |
| P99 延迟 | < 5 ms | 99%的消息延迟 |
| 最大并发 | 10,000+ msg/sec | 多个消费者 |

---

## 🔍 性能监控和调优

### 查看 RabbitMQ 管理面板
```
打开浏览器访问: http://localhost:15672
默认用户名: guest
默认密码: guest
```

### 主要监控指标

1. **Queue Depth**：队列消息堆积数
   - 如果持续上升，消费速度跟不上生产速度
   - 应增加消费者数量或优化消费逻辑

2. **Consumer Throughput**：消费吞吐量
   - 应该接近生产吞吐量
   - 低于预期说明消费端有瓶颈

3. **Queue Latency**：端到端延迟
   - 应保持在毫秒级别
   - 高于 100ms 说明有性能问题

### 调优建议

#### 1. 增加消费者数量
```java
// 在 NotificationService 中调整并发消费数
@RabbitListener(
    queues = RabbitConfig.COMMENT_QUEUE,
    concurrency = "10-20"  // 从 10 个并发线程增加到 20 个
)
public void onMessage(CommentEvent message) {
    // ...
}
```

#### 2. 调整预取数量
```java
// RabbitConfig.java
@Bean
public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setPrefetchCount(100);  // 一次预取 100 条消息
    factory.setConcurrentConsumers(10);  // 10 个并发消费者
    factory.setMaxConcurrentConsumers(20);  // 最多 20 个并发消费者
    return factory;
}
```

#### 3. 优化消息序列化
```java
// 使用快速序列化库（如 Kryo）
@Bean
public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

#### 4. 异步处理数据库操作
```java
@Async
@RabbitListener(queues = RabbitConfig.COMMENT_QUEUE)
public void onMessage(CommentEvent message) {
    // 异步保存到数据库，不阻塞消费线程
    notificationRepository.save(notification);
    wsPusher.notifyUserComment(message.getToUserId(), n);
}
```

---

## ⚠️ 常见问题排查

### 问题1：吞吐量低于预期

**排查步骤：**
1. 检查 RabbitMQ 连接是否正常
   ```bash
   docker logs rabbitmq_weibo | tail -20
   ```

2. 查看队列堆积情况
   - 访问 http://localhost:15672 管理面板

3. 检查消费端日志是否有错误
   ```bash
   docker logs backend | grep -i "error\|exception"
   ```

### 问题2：偶发性消息丢失

**原因分析：**
- 队列设置了 `x-max-length` 导致消息被丢弃
- 消费端 ack 异常

**解决方案：**
```java
// RabbitConfig.java 
@Bean
public Queue commentQueue() {
    return QueueBuilder
        .durable(COMMENT_QUEUE)
        .arguments(arguments -> {
            arguments.put("x-max-length", 500000);  // 增大队列长度
            arguments.put("x-message-ttl", 86400000);  // 消息 24 小时后过期
        })
        .build();
}
```

### 问题3：消费延迟突然增加

**可能原因：**
- 数据库写入变慢
- WebSocket 推送阻塞
- 消费线程池耗尽

**解决方案：**
1. 检查数据库性能
   ```sql
   SELECT * FROM pg_stat_statements ORDER BY total_time DESC LIMIT 5;
   ```

2. 使用线程池监控
   ```java
   ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
   executor.setCorePoolSize(10);
   executor.setMaxPoolSize(20);
   executor.setQueueCapacity(100);
   ```

---

## 📈 压测场景建议

### 场景1：正常生产环境
- 消息生产速率：500 msg/sec
- 测试时长：5 分钟
- 目标：P99 延迟 < 5ms

### 场景2：流量峰值
- 消息生产速率：2000 msg/sec
- 测试时长：2 分钟
- 目标：P99 延迟 < 20ms，无消息丢失

### 场景3：极限压力
- 消息生产速率：10000 msg/sec
- 测试时长：1 分钟
- 目标：找出系统瓶颈

---

## 🎯 总结

三种压测方式对比：

| 方式 | 启动速度 | 测试准确性 | 依赖 | 推荐场景 |
|------|--------|----------|------|---------|
| Pika | ⚡ 快 | ⭐⭐⭐ | Python | 快速验证、CI/CD |
| Spring Boot | ⭐⭐ 中等 | ⭐⭐⭐⭐⭐ | Docker | 完整场景模拟 |
| PerfTest | ⭐⭐ 中等 | ⭐⭐⭐⭐ | Java | 专业性能测试 |

**推荐工作流：**
1. 开发时用 **Pika** 快速验证
2. 集成测试用 **Spring Boot**
3. 生产部署前用 **PerfTest** 做最终验证
