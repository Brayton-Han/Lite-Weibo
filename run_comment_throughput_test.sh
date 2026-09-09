#!/bin/bash

# Comment Queue 吞吐量测试启动脚本

echo "🚀 Comment Queue 吞吐量测试工具"
echo "=================================="
echo ""
echo "选择测试方式:"
echo "1) Python Pika (快速，轻量级)"
echo "2) Spring Boot (完整测试)"
echo "3) RabbitMQ PerfTest (官方工具)"
echo ""
read -p "请选择 (1-3): " choice

case $choice in
    1)
        echo ""
        echo "📦 检查 Python 依赖..."
        python3 -m pip install pika > /dev/null 2>&1
        
        echo "⚙️  配置参数:"
        read -p "吞吐量 (msg/sec, 默认 500): " throughput
        throughput=${throughput:-500}
        
        read -p "测试时长 (秒, 默认 30): " duration
        duration=${duration:-30}
        
        echo ""
        echo "正在启动 Pika 测试... (吞吐量=$throughput, 时长=$duration 秒)"
        echo ""
        
        # 修改脚本中的参数
        sed -i '' "s/MESSAGES_PER_SECOND = [0-9]\+/MESSAGES_PER_SECOND = $throughput/" pika/test_comment_throughput.py
        sed -i '' "s/DURATION_SECONDS = [0-9]\+/DURATION_SECONDS = $duration/" pika/test_comment_throughput.py
        
        python3 pika/test_comment_throughput.py
        ;;
        
    2)
        echo ""
        echo "⚙️  配置参数:"
        read -p "吞吐量 (msg/sec, 默认 1000): " throughput
        throughput=${throughput:-1000}
        
        read -p "测试时长 (秒, 默认 30): " duration
        duration=${duration:-30}
        
        echo ""
        echo "📦 确保 Docker 服务已启动..."
        docker ps > /dev/null 2>&1
        if [ $? -ne 0 ]; then
            echo "❌ Docker 未启动，请先启动 Docker"
            exit 1
        fi
        
        echo "检查 RabbitMQ 服务..."
        docker ps | grep rabbitmq > /dev/null
        if [ $? -ne 0 ]; then
            echo "⚠️  RabbitMQ 未运行，正在启动..."
            docker-compose up -d
            echo "等待 RabbitMQ 启动... (10秒)"
            sleep 10
        fi
        
        echo ""
        echo "正在启动 Spring Boot 压测... (吞吐量=$throughput, 时长=$duration 秒)"
        echo ""
        
        cd backend
        THROUGHPUT=$throughput DURATION=$duration TEST_MODE=comment-throughput mvn spring-boot:run -q
        cd ..
        ;;
        
    3)
        echo ""
        echo "🔗 需要先安装 RabbitMQ PerfTest:"
        echo ""
        echo "macOS (安装 Homebrew):"
        echo "  brew install rabbitmq-perf-test"
        echo ""
        echo "其他系统:"
        echo "  参考: https://github.com/rabbitmq/rabbitmq-perf-test"
        echo ""
        read -p "已安装 PerfTest? (y/n): " installed
        
        if [ "$installed" = "y" ]; then
            echo ""
            read -p "吞吐量 (msg/sec, 默认 500): " throughput
            throughput=${throughput:-500}
            
            read -p "消息数 (默认 15000): " messages
            messages=${messages:-15000}
            
            echo ""
            echo "正在启动 PerfTest (吞吐量=$throughput, 消息数=$messages)..."
            echo ""
            
            runjava com.rabbitmq.perf.PerfTest \
              --exchange notification.exchange \
              --routing-key notification.comment \
              --rate $throughput \
              --queues 1 \
              --messages $messages
        else
            echo "❌ 请先安装 RabbitMQ PerfTest"
            exit 1
        fi
        ;;
        
    *)
        echo "❌ 无效选择"
        exit 1
        ;;
esac

echo ""
echo "✅ 测试完成！"
