package snake.application.gateway;

import java.util.UUID;
import org.redisson.api.RedissonClient;
import snake.application.gateway.heartbeat.DefaultHeartbeatService;
import snake.application.gateway.heartbeat.HeartbeatService;
import snake.application.gateway.session.ClientSession;
import snake.application.gateway.session.DefaultSessionManager;
import snake.application.gateway.session.SessionManager;
import snake.common.Config;
import snake.common.IConfigProvider;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.auth.GatewayAuthClient;
import snake.infrastructure.messaging.MessageBus;
import snake.infrastructure.persistence.DatabaseManager;
import snake.infrastructure.persistence.PropertiesConfigProvider;
import snake.infrastructure.persistence.RedissonManager;

public class GatewayMain {
  public static void main(String[] args) {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : Config.GATEWAY_DEFAULT_PORT;
    boolean distributedMode = Boolean.parseBoolean(System.getProperty("distributed.mode", "false"));
    String gatewayId =
        System.getProperty(
            "node.id", "gateway-" + port + "-" + UUID.randomUUID().toString().substring(0, 8));
    String authServiceUrl = System.getProperty("auth.service.url", Config.AUTH_SERVICE_URL);

    ILogger logger = Logger.getInstance();
    logger.info("Starting Reactor Gateway " + gatewayId + " on port " + port);

    IConfigProvider config = new PropertiesConfigProvider("config.properties");
    DatabaseManager dbManager = DatabaseManager.getInstance(config);

    final RedissonClient redisson;
    final DistributedCoordinator coordinator;
    if (distributedMode) {
      redisson = RedissonManager.getInstance(config);
      coordinator = new DistributedCoordinator(redisson, gatewayId);
    } else {
      redisson = null;
      coordinator = null;
    }

    MessageBus messageBus = null;
    try {
      messageBus = new MessageBus();
    } catch (Exception e) {
      logger.error("Failed to connect to RabbitMQ: " + e.getMessage());
      System.exit(1);
    }

    SessionManager sessionManager = new DefaultSessionManager();

    HeartbeatService heartbeatService =
        new DefaultHeartbeatService(
            session -> {
              ClientSession client = (ClientSession) session;
              if (client != null && client.isActive()) client.close();
            },
            coordinator);

    GatewayAuthClient authClient = new GatewayAuthClient(authServiceUrl);

    ReactorGateway gateway =
        new ReactorGateway(
            port, sessionManager, heartbeatService, authClient, coordinator, messageBus, gatewayId);

    // 房间列表更新广播（RabbitMQ）
    if (messageBus != null) {
      try {
        messageBus.subscribeRoomListUpdates(gatewayId, () -> gateway.sendRoomListToLobby());
        logger.info("Gateway subscribed to room list updates via RabbitMQ");
      } catch (Exception e) {
        logger.error("Failed to subscribe to room list updates: " + e.getMessage());
      }
    }

    // 定向消息订阅（RabbitMQ）
    if (messageBus != null) {
      try {
        // 文本消息订阅（JOIN_OK, ERROR 等）
        messageBus.subscribeGateway(
            gatewayId,
            (routingKey, message) -> {
              String[] parts = routingKey.split("\\.");
              String username = (parts.length >= 5) ? parts[4] : null; // 改为 parts[4]
              if (username == null) return;
              ClientSession session = sessionManager.getSessionByUsername(username);
              if (session != null && session.isActive()) {
                session.sendMessage(message); // 文本处理，自动加 0x01 前缀
              }
            });

        // 二进制消息订阅（游戏状态）
        messageBus.subscribeGatewayBinary(
            gatewayId,
            (routingKey, data) -> {
              String[] parts = routingKey.split("\\.");
              String username = (parts.length >= 5) ? parts[4] : null; // 改为 parts[4]
              if (username == null) return;
              ClientSession session = sessionManager.getSessionByUsername(username);
              if (session != null && session.isActive()) {
                // data 已包含 0x00 前缀，直接发送（绕过 sendBinary 避免二次编码）
                session.getChannel().writeAndFlush(data);
              }
            });
      } catch (Exception e) {
        logger.error("Failed to subscribe to player messages: " + e.getMessage());
      }
    }

    try {
      gateway.start();
      logger.info("Gateway " + gatewayId + " started on port " + port);
    } catch (Exception e) {
      logger.error("Failed to start gateway: " + e.getMessage());
      System.exit(1);
    }

    final MessageBus finalMessageBus = messageBus;
    final RedissonClient finalRedisson = redisson;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down gateway " + gatewayId + "...");
                  gateway.stop();
                  if (finalMessageBus != null) finalMessageBus.close();
                  if (finalRedisson != null) finalRedisson.shutdown();
                  dbManager.shutdown();
                }));
  }
}
