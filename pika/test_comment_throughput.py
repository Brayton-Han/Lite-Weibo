import pika
import json
import time
import uuid
import threading
from datetime import datetime

# Configuration
MESSAGES_PER_SECOND = 500  # 可根据需要调整
DURATION_SECONDS = 30      # 测试时长
RABBITMQ_HOST = 'localhost'
QUEUE_NAME = 'notification.comment.queue'

class CommentQueueTester:
    def __init__(self):
        self.connection = pika.BlockingConnection(
            pika.ConnectionParameters(RABBITMQ_HOST, heartbeat=600)
        )
        self.channel = self.connection.channel()
        self.channel.queue_declare(queue=QUEUE_NAME, durable=True)
        
        # 生产者统计
        self.produced = 0
        self.consumed = 0
        self.latencies = []
        self.start_time = None
        
    def produce_comments(self):
        """发送评论消息"""
        print(f"[{datetime.now().strftime('%H:%M:%S')}] 开始生产 comment 消息...")
        self.start_time = time.time()
        interval = 1.0 / MESSAGES_PER_SECOND
        
        end_time = self.start_time + DURATION_SECONDS
        while time.time() < end_time:
            msg = {
                "eventType": "COMMENT",
                "msgId": str(uuid.uuid4()),
                "fromUserId": 1,
                "toUserId": 2,
                "postId": 100,
                "content": "Test comment",
                "timestamp": time.time()
            }
            
            self.channel.basic_publish(
                exchange='',
                routing_key=QUEUE_NAME,
                body=json.dumps(msg),
                properties=pika.BasicProperties(
                    delivery_mode=2  # 持久化消息
                )
            )
            self.produced += 1
            time.sleep(interval)
        
        print(f"[{datetime.now().strftime('%H:%M:%S')}] 生产完毕，共发送 {self.produced} 条消息")

    def consume_comments(self):
        """消费评论消息并测量延迟"""
        print(f"[{datetime.now().strftime('%H:%M:%S')}] 开始消费 comment 消息...")
        
        def callback(ch, method, properties, body):
            msg = json.loads(body)
            latency = (time.time() - msg["timestamp"]) * 1000
            self.latencies.append(latency)
            self.consumed += 1
            
            if self.consumed % 100 == 0:
                print(f"已消费 {self.consumed} 条消息，当前延迟: {latency:.2f} ms")
            
            ch.basic_ack(delivery_tag=method.delivery_tag)
        
        self.channel.basic_qos(prefetch_count=100)
        self.channel.basic_consume(
            queue=QUEUE_NAME,
            on_message_callback=callback
        )
        
        try:
            self.channel.start_consuming()
        except KeyboardInterrupt:
            self.channel.stop_consuming()
            self.connection.close()

    def run_test(self):
        """运行测试"""
        # 启动消费者线程
        consumer_thread = threading.Thread(target=self.consume_comments, daemon=True)
        consumer_thread.start()
        
        time.sleep(1)  # 等待消费者启动
        
        # 主线程生产消息
        self.produce_comments()
        
        # 等待消费者处理所有消息
        time.sleep(5)
        
        # 输出测试结果
        self.print_results()
    
    def print_results(self):
        """打印测试结果"""
        elapsed = time.time() - self.start_time
        print("\n" + "="*60)
        print("📊 Comment Queue 吞吐量测试结果")
        print("="*60)
        print(f"测试时长:        {elapsed:.2f} 秒")
        print(f"发送消息数:      {self.produced} 条")
        print(f"消费消息数:      {self.consumed} 条")
        print(f"实际吞吐量:      {self.produced/elapsed:.2f} msg/sec")
        
        if self.latencies:
            avg_latency = sum(self.latencies) / len(self.latencies)
            max_latency = max(self.latencies)
            min_latency = min(self.latencies)
            
            # 计算 P95, P99
            sorted_latencies = sorted(self.latencies)
            p95 = sorted_latencies[int(len(sorted_latencies) * 0.95)]
            p99 = sorted_latencies[int(len(sorted_latencies) * 0.99)]
            
            print(f"\n延迟统计 (毫秒):")
            print(f"  平均:          {avg_latency:.2f} ms")
            print(f"  最小:          {min_latency:.2f} ms")
            print(f"  最大:          {max_latency:.2f} ms")
            print(f"  P95:           {p95:.2f} ms")
            print(f"  P99:           {p99:.2f} ms")
        
        print("="*60 + "\n")

if __name__ == "__main__":
    tester = CommentQueueTester()
    tester.run_test()
