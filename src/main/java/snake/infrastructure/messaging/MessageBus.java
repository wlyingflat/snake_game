package snake.infrastructure.messaging;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import snake.common.IConfigProvider;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.PropertiesConfigProvider;

public class MessageBus implements AutoCloseable {
  private final Connection connection;
  private final WorkerMessageChannel workerChannel;
  private final GatewayPlayerChannel playerChannel;
  private final RoomListChannel roomListChannel;
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

    try (com.rabbitmq.client.Channel ch = connection.createChannel()) {
      ch.exchangeDeclare("gateway.topic", "topic", true);
      ch.exchangeDeclare("room.list.fanout", "fanout", true);
    }

    this.workerChannel = new WorkerMessageChannel(connection);
    this.playerChannel = new GatewayPlayerChannel(connection);
    this.roomListChannel = new RoomListChannel(connection);
    logger.info("MessageBus connected to RabbitMQ");
  }

  // Worker 端：接收 binary 消息（Protobuf 格式）
  public void startWorkerConsumer(String workerId, Consumer<byte[]> messageHandler)
      throws IOException {
    workerChannel.startConsumer(workerId, messageHandler);
  }

  // 向 Worker 发送 Protobuf 二进制消息
  public void sendToWorker(String workerId, byte[] protobufMessage) {
    workerChannel.sendToWorker(workerId, protobufMessage);
  }

  // 向某玩家发送文本消息（JOIN_OK 等）
  public void publishToPlayer(String gatewayId, String username, String message) {
    playerChannel.publishToPlayer(gatewayId, username, message);
  }

  // 向某玩家发送二进制消息（游戏状态 / Protobuf 业务消息）
  public void publishBinaryToPlayer(String gatewayId, String username, byte[] data, byte subType) {
    playerChannel.publishBinaryToPlayer(gatewayId, username, data, subType);
  }

  public void subscribeGateway(String gatewayId, BiConsumer<String, String> messageConsumer)
      throws IOException {
    playerChannel.subscribeGateway(gatewayId, messageConsumer);
  }

  public void subscribeGatewayBinary(String gatewayId, BiConsumer<String, byte[]> consumer)
      throws IOException {
    playerChannel.subscribeGatewayBinary(gatewayId, consumer);
  }

  public void publishRoomListUpdate() {
    roomListChannel.publishUpdate();
  }

  public void subscribeRoomListUpdates(String gatewayId, Runnable callback) throws IOException {
    roomListChannel.subscribeGateway(gatewayId, callback);
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
