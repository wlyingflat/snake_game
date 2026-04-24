package snake.mq;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import snake.base.IConfigProvider;
import snake.base.ILogger;
import snake.base.Logger;
import snake.persistence.PropertiesConfigProvider;

/** RabbitMQ 消息总线，替代 Redis Pub/Sub 进行 Gateway <-> Worker 指令传输。 新增 Worker → Gateway 玩家消息的持久化队列支持。 */
public class MessageBus implements AutoCloseable {

  private static final String QUEUE_PREFIX = "worker.commands.";
  private static final String GATEWAY_EXCHANGE = "gateway.topic";

  private final Connection connection;
  private final ILogger logger = Logger.getInstance();

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
    factory.setAutomaticRecoveryEnabled(true);
    factory.setNetworkRecoveryInterval(5000);

    this.connection = factory.newConnection();
    // 声明 topic exchange（持久化）
    try (Channel ch = connection.createChannel()) {
      ch.exchangeDeclare(GATEWAY_EXCHANGE, "topic", true);
    }
    logger.info("MessageBus connected to RabbitMQ at " + host + ":" + port);
  }

  // ==================== Worker 指令队列（原有） ====================

  public void startWorkerConsumer(String workerId, Consumer<String> messageHandler)
      throws IOException {
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

  public void sendToWorker(String workerId, String message) {
    try (Channel channel = connection.createChannel()) {
      String queue = getQueueName(workerId);
      channel.queueDeclare(queue, true, false, false, null);
      channel.basicPublish(
          "",
          queue,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) { // 捕获 IOException 和 TimeoutException
      logger.error("Failed to send message to worker " + workerId + ": " + e.getMessage());
    }
  }

  private String getQueueName(String workerId) {
    return QUEUE_PREFIX + workerId;
  }

  // ==================== Worker → Gateway 玩家消息（新增） ====================

  /**
   * Worker 发送消息给指定 Gateway 上的玩家（持久化，可靠）
   *
   * @param gatewayId 目标网关 ID
   * @param username 玩家名
   * @param message 消息内容（JSON）
   */
  public void publishToPlayer(String gatewayId, String username, String message) {
    try (Channel ch = connection.createChannel()) {
      String routingKey = String.format("gateway.%s.player.%s", gatewayId, username);
      ch.basicPublish(
          GATEWAY_EXCHANGE,
          routingKey,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) { // 捕获 IOException 和 TimeoutException
      logger.error("Failed to publish to player " + username + ": " + e.getMessage());
    }
  }

  /**
   * Gateway 订阅属于自己的所有玩家消息
   *
   * @param gatewayId 本 Gateway ID
   * @param messageConsumer 回调，参数为 (routingKey, messageBody)
   */
  public void subscribeGateway(String gatewayId, BiConsumer<String, String> messageConsumer)
      throws IOException {
    Channel channel = connection.createChannel();
    // 持久化、非独占、非自动删除的队列（保证重启后队列存活）
    String queueName = "gateway.queue." + gatewayId;
    channel.queueDeclare(queueName, true, false, false, null);
    // 绑定模式：gateway.{gatewayId}.player.*
    String bindingPattern = String.format("gateway.%s.player.*", gatewayId);
    channel.queueBind(queueName, GATEWAY_EXCHANGE, bindingPattern);
    channel.basicQos(1);
    channel.basicConsume(
        queueName,
        false,
        (consumerTag, delivery) -> {
          String routingKey = delivery.getEnvelope().getRoutingKey();
          String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
          try {
            messageConsumer.accept(routingKey, body);
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
          } catch (Exception e) {
            logger.error("Error handling message: " + e.getMessage());
            try {
              channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            } catch (IOException ex) {
              logger.error("Failed to nack message: " + ex.getMessage());
            }
          }
        },
        consumerTag -> {});
    logger.info("Gateway " + gatewayId + " subscribed to queue " + queueName);
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
