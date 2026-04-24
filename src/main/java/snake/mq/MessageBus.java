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

/**
 * RabbitMQ 消息总线，替代 Redis Pub/Sub 进行 Gateway <-> Worker 指令传输。 新增 Worker → Gateway
 * 玩家消息的持久化队列支持，以及房间列表广播。
 */
public class MessageBus implements AutoCloseable {

  private static final String QUEUE_PREFIX = "worker.commands.";
  private static final String GATEWAY_EXCHANGE = "gateway.topic";
  private static final String ROOM_LIST_EXCHANGE = "room.list.fanout"; // 新增

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
    // 声明 topic exchange（用于 Worker → Gateway 定向消息）
    // 声明 fanout exchange（用于房间列表广播）
    try (Channel ch = connection.createChannel()) {
      ch.exchangeDeclare(GATEWAY_EXCHANGE, "topic", true);
      ch.exchangeDeclare(ROOM_LIST_EXCHANGE, "fanout", true);
    }
    logger.info("MessageBus connected to RabbitMQ at " + host + ":" + port);
  }

  // ==================== Worker 指令队列 ====================

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
    } catch (Exception e) {
      logger.error("Failed to send message to worker " + workerId + ": " + e.getMessage());
    }
  }

  private String getQueueName(String workerId) {
    return QUEUE_PREFIX + workerId;
  }

  // ==================== Worker → Gateway 玩家定向消息 ====================

  public void publishToPlayer(String gatewayId, String username, String message) {
    try (Channel ch = connection.createChannel()) {
      String routingKey = String.format("gateway.%s.player.%s", gatewayId, username);
      ch.basicPublish(
          GATEWAY_EXCHANGE,
          routingKey,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.error("Failed to publish to player " + username + ": " + e.getMessage());
    }
  }

  public void subscribeGateway(String gatewayId, BiConsumer<String, String> messageConsumer)
      throws IOException {
    Channel channel = connection.createChannel();
    String queueName = "gateway.queue." + gatewayId;
    channel.queueDeclare(queueName, true, false, false, null);
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

  // ==================== 房间列表广播（新增） ====================

  /** 发布房间列表更新事件（由 Worker 在房间状态变化时调用） */
  public void publishRoomListUpdate() {
    try (Channel ch = connection.createChannel()) {
      ch.basicPublish(
          ROOM_LIST_EXCHANGE,
          "",
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          "UPDATE".getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.error("Failed to publish room list update: " + e.getMessage());
    }
  }

  /**
   * Gateway 订阅房间列表更新事件
   *
   * @param gatewayId 本 Gateway ID（用于创建独占队列，区分不同 Gateway）
   * @param callback 收到事件时回调（通常触发刷新房间列表并推送给大厅玩家）
   */
  public void subscribeRoomListUpdates(String gatewayId, Runnable callback) throws IOException {
    Channel channel = connection.createChannel();
    String queueName = "roomlist.gateway." + gatewayId;
    channel.queueDeclare(queueName, true, false, false, null);
    channel.queueBind(queueName, ROOM_LIST_EXCHANGE, "");
    channel.basicConsume(
        queueName,
        true,
        (consumerTag, delivery) -> {
          callback.run();
        },
        consumerTag -> {});
    logger.info("Gateway " + gatewayId + " subscribed to room list updates");
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
