import pika
import json
import time

connection = pika.BlockingConnection(
    pika.ConnectionParameters('localhost')
)

channel = connection.channel()

channel.queue_declare(queue='notification_queue', durable=True)

channel.basic_qos(prefetch_count=100)

print("Consumer started")

def callback(ch, method, properties, body):
    msg = json.loads(body)

    t0 = msg["t0"]
    latency = time.time() - t0

    # 模拟 websocket 推送
    # 实际项目这里是 websocket.send()

    print(f"latency: {latency*1000:.2f} ms")

    ch.basic_ack(delivery_tag=method.delivery_tag)

channel.basic_consume(
    queue='notification_queue',
    on_message_callback=callback
)

channel.start_consuming()