import pika
import json
import time
import uuid

connection = pika.BlockingConnection(
    pika.ConnectionParameters('localhost')
)

channel = connection.channel()

channel.queue_declare(queue='notification_queue', durable=True)

rate = 1500   # 每秒发送多少条
interval = 1.0 / rate

print("Start producing...")

while True:
    msg = {
        "msgId": str(uuid.uuid4()),
        "t0": time.time()
    }

    channel.basic_publish(
        exchange='',
        routing_key='notification_queue',
        body=json.dumps(msg)
    )

    time.sleep(interval)