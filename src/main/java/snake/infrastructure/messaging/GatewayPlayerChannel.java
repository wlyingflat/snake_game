package snake.infrastructure.messaging;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import snake.common.ILogger;
import snake.common.Logger;

/** 负责 Worker → Gateway 玩家定向消息的发布与订阅。 */
public class GatewayPlayerChannel {
  private static final String GATEWAY_EXCHANGE = "gateway.topic";
  private final Connection connection;
  private final ILogger logger = Logger.getInstance();

  public GatewayPlayerChannel(Connection connection) {
    this.connection = connection;
  }

  /** 向某个玩家的 Gateway 发送消息。 */
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

  /** Gateway 订阅属于自己的玩家消息队列。 */
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
}
