package snake.mq;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import snake.base.IConfigProvider;
import snake.base.ILogger;
import snake.base.Logger;
import snake.persistence.PropertiesConfigProvider;

/**
 * RabbitMQ 消息总线，替代 Redis Pub/Sub 进行 Gateway <-> Worker 指令传输。
 *
 * <p>设计要点： - 每个 Worker 对应一个持久化队列（命名规则：worker.commands.{workerId}） - Gateway 发送消息时直接 publish 到目标
 * Worker 的队列 - Worker 启动时声明队列并开始消费 - 连接自动恢复，消息持久化（可配置）
 */
public class MessageBus implements AutoCloseable {

  private static final String QUEUE_PREFIX = "worker.commands.";

  private final Connection connection;
  private final ILogger logger = Logger.getInstance();

  /** 创建 MessageBus 实例并建立连接。 配置从 PropertiesConfigProvider 读取（支持 config.properties 及系统属性覆盖）。 */
  public MessageBus() throws IOException, TimeoutException {
    IConfigProvider config = new PropertiesConfigProvider("config.properties");

    String host = config.getString("mq.host", "localhost");
    int port = config.getInt("mq.port", 5672);
    String user = config.getString("mq.username", "guest");
    String pass = config.getString("mq.password", "guest");

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setPort(port);
    factory.setUsername(user);
    factory.setPassword(pass);
    factory.setAutomaticRecoveryEnabled(true); // 自动重连
    factory.setNetworkRecoveryInterval(5000); // 重连间隔 5s

    this.connection = factory.newConnection();
    logger.info("MessageBus connected to RabbitMQ at " + host + ":" + port);
  }

  /**
   * 启动 Worker 端消费者，开始监听指令队列。
   *
   * @param workerId 当前 Worker 的唯一标识
   * @param messageHandler 消息处理回调（在 RabbitMQ 的消费者线程中调用）
   */
  public void startWorkerConsumer(String workerId, Consumer<String> messageHandler)
      throws IOException {
    String queueName = getQueueName(workerId);
    // 为每个消费者创建独立的 Channel
    Channel channel = connection.createChannel();
    // 声明持久化队列（非排他，非自动删除）
    channel.queueDeclare(queueName, true, false, false, null);
    // 每次只预取一条消息，保证同一个 Worker 上的房间顺序处理（可选，默认即可）
    channel.basicQos(1);
    // 自动确认（简单可靠）
    channel.basicConsume(
        queueName,
        true,
        new DefaultConsumer(channel) {
          @Override
          public void handleDelivery(
              String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
              throws IOException {
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

  /**
   * Gateway 向指定 Worker 发送指令消息。 每次调用创建临时 Channel 并立即关闭，保证线程安全，对低 QPS 场景足够。
   *
   * @param workerId 目标 Worker ID
   * @param message 消息内容（JSON 字符串）
   */
  public void sendToWorker(String workerId, String message) {
    try (Channel channel = connection.createChannel()) {
      String queue = getQueueName(workerId);
      // 声明队列以保证存在（幂等）
      channel.queueDeclare(queue, true, false, false, null);
      channel.basicPublish(
          "",
          queue,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          message.getBytes(StandardCharsets.UTF_8));
    } catch (IOException | TimeoutException e) {
      logger.error("Failed to send message to worker " + workerId + ": " + e.getMessage());
    }
  }

  private String getQueueName(String workerId) {
    return QUEUE_PREFIX + workerId;
  }

  @Override
  public void close() {
    if (connection != null && connection.isOpen()) {
      try {
        connection.close();
      } catch (IOException e) {
        logger.error("Error closing RabbitMQ connection: " + e.getMessage());
      }
    }
  }
}
