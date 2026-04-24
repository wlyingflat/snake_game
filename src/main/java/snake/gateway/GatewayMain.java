package snake.gateway;

import java.util.UUID;
import org.redisson.api.RedissonClient;
import snake.base.*;
import snake.distributed.DistributedCoordinator;
import snake.gateway.auth.GatewayAuthClient;
import snake.gateway.dispatcher.MessageDispatcher;
import snake.gateway.heartbeat.DefaultHeartbeatService;
import snake.gateway.heartbeat.HeartbeatService;
import snake.gateway.reactor.ReactorGateway;
import snake.gateway.session.ClientSession;
import snake.gateway.session.DefaultSessionManager;
import snake.gateway.session.SessionManager;
import snake.persistence.DatabaseManager;
import snake.persistence.PropertiesConfigProvider;
import snake.persistence.redis.RedissonManager;

/** Reactor Gateway 启动入口 */
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

    SessionManager sessionManager = new DefaultSessionManager();

    HeartbeatService heartbeatService =
        new DefaultHeartbeatService(
            session -> {
              ClientSession client = (ClientSession) session;
              if (client != null && !client.closed) client.close();
            },
            coordinator);

    GatewayAuthClient authClient = new GatewayAuthClient(authServiceUrl);
    MessageDispatcher dispatcher = new MessageDispatcher(coordinator, gatewayId);

    ReactorGateway gateway =
        new ReactorGateway(
            port, sessionManager, heartbeatService, dispatcher, authClient, coordinator, gatewayId);

    if (coordinator != null) {
      coordinator.subscribeRoomListUpdates(
          (channel, msg) -> {
            gateway.sendRoomListToLobby();
          });

      final DistributedCoordinator finalCoordinator = coordinator;
      coordinator.subscribeGatewayMessages(
          gatewayId,
          (pattern, channel, msg) -> {
            String ch = channel.toString();
            String[] parts = ch.split(":");
            if (parts.length >= 4) {
              String username = parts[3];
              ClientSession session = sessionManager.getSessionByUsername(username);
              if (session != null && !session.closed) {
                session.sendMessage(msg);
              }
            }
          });
    }

    try {
      gateway.start();
      logger.info("Gateway " + gatewayId + " started on port " + port);
    } catch (Exception e) {
      logger.error("Failed to start gateway: " + e.getMessage());
      System.exit(1);
    }

    final RedissonClient finalRedisson = redisson;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down gateway " + gatewayId + "...");
                  gateway.stop();
                  if (finalRedisson != null) finalRedisson.shutdown();
                  dbManager.shutdown();
                }));
  }
}
