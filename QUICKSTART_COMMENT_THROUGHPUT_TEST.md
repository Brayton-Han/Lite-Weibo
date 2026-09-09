# 快速开始：Comment 队列吞吐量测试

## 🚀 最快开始方式（< 2 分钟）

### 方式1：使用交互式脚本（推荐）

```bash
# 进入项目根目录
cd /Users/brayton/Desktop/Lite-Weibo

# 运行测试脚本
./run_comment_throughput_test.sh
```

然后按照提示选择测试方式和参数。

---

### 方式2：直接使用 Pika（最快）

```bash
cd pika
pip install pika
python test_comment_throughput.py
```

**预期耗时：30 秒** ✅

---

### 方式3：使用 Spring Boot（完整测试）

```bash
# 确保 Docker 服务已启动
docker-compose up -d

# 等待服务启动（约 20 秒）
sleep 20

# 运行压测
cd backend
TEST_MODE=comment-throughput mvn spring-boot:run
```

**预期耗时：1-2 分钟**

---

## 📊 查看实时数据

### RabbitMQ 管理面板
```
地址：http://localhost:15672
用户：guest
密码：guest
```

**重点监控指标：**
- `notification.comment.queue` - 队列深度
- Ready - 待消费消息数
- Total - 消费速率

---

## 🔧 调整测试参数

### Pika 脚本
编辑 `pika/test_comment_throughput.py`:
```python
MESSAGES_PER_SECOND = 1000  # 改为需要的吞吐量
DURATION_SECONDS = 60       # 改为需要的时长
```

### Spring Boot
使用环境变量：
```bash
THROUGHPUT=2000 DURATION=60 TEST_MODE=comment-throughput mvn spring-boot:run
```

---

## 📈 理解测试结果

### 关键指标

| 指标 | 说明 | 良好值 |
|------|------|--------|
| 实际吞吐量 | 实际达到的 msg/sec | >= 目标值的 95% |
| 平均延迟 | 消息从发送到消费的平均时间 | < 2 ms |
| P99 延迟 | 99% 消息的延迟 | < 10 ms |
| 消费成功率 | 成功消费的消息比例 | 100% |

### 示例输出解读

```
实际吞吐量: 499.50 msg/sec        ✅ 目标 500，达到 99.9%
平均延迟: 1.23 ms                 ✅ 远低于 2ms
P99 延迟: 3.45 ms                 ✅ 在可接受范围
消费成功率: 100%                  ✅ 无消息丢失
```

---

## ❌ 验收标准

### 生产环境要求

- ✅ **吞吐量** ≥ 1000 msg/sec
- ✅ **平均延迟** < 5 ms
- ✅ **P99 延迟** < 20 ms
- ✅ **成功率** > 99.99%

### 当前性能基准（默认配置）

```
吞吐量: 500-1000 msg/sec
平均延迟: 1-2 ms
P99 延迟: 2-5 ms
成功率: 100%
```

---

## 🐛 常见问题

### Q: 测试显示低吞吐量
**A:** 检查：
1. Docker 服务状态：`docker ps`
2. RabbitMQ 连接：访问 http://localhost:15672
3. 后端日志：`docker logs backend | tail -20`

### Q: 延迟突然增加
**A:** 可能原因：
1. 数据库写入变慢 → 检查 PostgreSQL
2. WebSocket 推送堵塞 → 增加消费者并发数
3. 内存不足 → 检查容器资源

### Q: 消息堆积未消费
**A:** 解决方案：
```bash
# 增加消费者并发数
# 编辑 backend/src/main/java/com/brayton/weibo/service/NotificationService.java
# @RabbitListener(queues = ..., concurrency = "20-30")

# 重新启动
docker-compose restart backend
```

---

## 📚 详细文档

更详细的测试指南请查看：[COMMENT_QUEUE_THROUGHPUT_TEST.md](./COMMENT_QUEUE_THROUGHPUT_TEST.md)

包含内容：
- ✅ 三种压测方式详细对比
- ✅ 性能监控和调优建议
- ✅ 问题排查指南
- ✅ 压测场景建议

---

## 💡 Tips

1. **首次测试** → 用 Pika 脚本，快速验证
2. **完整测试** → 用 Spring Boot，模拟真实场景
3. **性能压测** → 用 PerfTest，专业工具
4. **持续监控** → 定期运行测试，对比历史数据

---

## 🎯 下一步

✅ 运行测试
```bash
./run_comment_throughput_test.sh
```

✅ 查看结果
```
根据输出确认吞吐量和延迟是否满足要求
```

✅ 优化（如需要）
```bash
参考 COMMENT_QUEUE_THROUGHPUT_TEST.md 的调优建议
```

---

**需要帮助？** 查看 [COMMENT_QUEUE_THROUGHPUT_TEST.md](./COMMENT_QUEUE_THROUGHPUT_TEST.md) 中的完整指南！
