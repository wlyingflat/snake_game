package snake.infrastructure.messaging;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import snake.common.ILogger;
import snake.common.Logger;

/** 负责 Worker 命令队列的发送与消费。 */
public class WorkerMessageChannel {
  private static final String QUEUE_PREFIX = "worker.commands.";
  private final Connection connection;
  private final ILogger logger = Logger.getInstance();

  public WorkerMessageChannel(Connection connection) {
    this.connection = connection;
  }

  /** 启动某个 Worker 的消费监听。 */
  public void startConsumer(String workerId, Consumer<String> messageHandler) throws IOException {
    String queueName = getQueueName(workerId);
    Channel channel = connection.createChannel();
    channel.queueDeclare(queueName, true, false, false, null);
    channel.basicQos(1);
    channel.basicConsume(
        queueName,
        true,
        new DefaultConsumer(channel) {
          @Override
          public void handleDelivery(
              String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
            String message = new String(body, StandardCharsets.UTF_8);
            try {
              messageHandler.accept(message);
            } catch (Exception e) {
              logger.error("Worker message handler error: " + e.getMessage());
            }
          }
        });
    logger.info("Worker consumer started on queue: " + queueName);
  }

  /** 向指定 Worker 发送一条指令。 */
  public void sendToWorker(String workerId, String message) {
    try (Channel channel = connection.createChannel()) {
      String queue = getQueueName(workerId);
      channel.queueDeclare(queue, true, false, false, null);
      channel.basicPublish(
          "",
          queue,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.error("Failed to send to worker " + workerId + ": " + e.getMessage());
    }
  }

  private String getQueueName(String workerId) {
    return QUEUE_PREFIX + workerId;
  }
}
