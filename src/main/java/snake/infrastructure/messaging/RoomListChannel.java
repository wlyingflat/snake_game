package snake.infrastructure.messaging;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import snake.common.ILogger;
import snake.common.Logger;

/** 负责房间列表更新的广播与订阅。 */
public class RoomListChannel {
  private static final String ROOM_LIST_EXCHANGE = "room.list.fanout";
  private final Connection connection;
  private final ILogger logger = Logger.getInstance();

  public RoomListChannel(Connection connection) {
    this.connection = connection;
  }

  /** 发布房间列表更新事件（由 Worker 调用）。 */
  public void publishUpdate() {
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

  /** Gateway 订阅房间列表更新事件。 */
  public void subscribeGateway(String gatewayId, Runnable callback) throws IOException {
    Channel channel = connection.createChannel();
    String queueName = "roomlist.gateway." + gatewayId;
    channel.queueDeclare(queueName, true, false, false, null);
    channel.queueBind(queueName, ROOM_LIST_EXCHANGE, "");
    channel.basicConsume(
        queueName, true, (consumerTag, delivery) -> callback.run(), consumerTag -> {});
    logger.info("Gateway " + gatewayId + " subscribed to room list updates");
  }
}
