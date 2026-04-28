package snake.infrastructure.messaging;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import snake.common.ILogger;
import snake.common.Logger;

public class GatewayPlayerChannel {
  private static final String GATEWAY_EXCHANGE = "gateway.topic";
  public static final String TEXT_QUEUE_PREFIX = "gateway.text.queue.";
  public static final String BINARY_QUEUE_PREFIX = "gateway.binary.queue.";
  private final Connection connection;
  private final ILogger logger = Logger.getInstance();

  public GatewayPlayerChannel(Connection connection) {
    this.connection = connection;
  }

  // ---------- 文本消息（仍用于 JOIN_OK, ERROR 等） ----------
  public void publishToPlayer(String gatewayId, String username, String message) {
    try (Channel ch = connection.createChannel()) {
      String routingKey = String.format("gateway.%s.player.text.%s", gatewayId, username);
      ch.basicPublish(
          GATEWAY_EXCHANGE,
          routingKey,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.error("Failed to publish text to player " + username + ": " + e.getMessage());
    }
  }

  public void subscribeGateway(String gatewayId, BiConsumer<String, String> messageConsumer)
      throws IOException {
    Channel channel = connection.createChannel();
    String queueName = TEXT_QUEUE_PREFIX + gatewayId;
    channel.queueDeclare(queueName, true, false, false, null);
    String bindingPattern = String.format("gateway.%s.player.text.*", gatewayId);
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
            logger.error("Error handling text message: " + e.getMessage());
            try {
              channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            } catch (IOException ex) {
              logger.error("Failed to nack text message: " + ex.getMessage());
            }
          }
        },
        consumerTag -> {});
    logger.info("Gateway " + gatewayId + " subscribed to text queue " + queueName);
  }

  // ---------- 二进制消息（含 Protobuf 业务消息） ----------
  public void publishBinaryToPlayer(String gatewayId, String username, byte[] data, byte subType) {
    byte[] framed = new byte[data.length + 1];
    framed[0] = subType;
    System.arraycopy(data, 0, framed, 1, data.length);
    try (Channel ch = connection.createChannel()) {
      String routingKey = String.format("gateway.%s.player.bin.%s", gatewayId, username);
      ch.basicPublish(
          GATEWAY_EXCHANGE, routingKey, MessageProperties.PERSISTENT_TEXT_PLAIN, framed);
    } catch (Exception e) {
      logger.error("Failed to publish binary to player " + username + ": " + e.getMessage());
    }
  }

  public void subscribeGatewayBinary(String gatewayId, BiConsumer<String, byte[]> consumer)
      throws IOException {
    Channel channel = connection.createChannel();
    String queueName = BINARY_QUEUE_PREFIX + gatewayId;
    channel.queueDeclare(queueName, true, false, false, null);
    String bindingPattern = String.format("gateway.%s.player.bin.*", gatewayId);
    channel.queueBind(queueName, GATEWAY_EXCHANGE, bindingPattern);
    channel.basicConsume(
        queueName,
        true,
        (consumerTag, delivery) -> {
          String routingKey = delivery.getEnvelope().getRoutingKey();
          byte[] body = delivery.getBody();
          try {
            consumer.accept(routingKey, body);
          } catch (Exception e) {
            logger.error("Error handling binary message: " + e.getMessage());
          }
        },
        consumerTag -> {});
    logger.info("Gateway " + gatewayId + " subscribed to binary queue " + queueName);
  }
}
