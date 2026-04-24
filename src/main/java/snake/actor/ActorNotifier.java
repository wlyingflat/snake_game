package snake.actor;

import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;
import snake.event.KafkaEventProducer;
import snake.event.PlayerDiedEvent;
import snake.event.ScoreChangedEvent;

/** Actor 的通知器 负责将消息发送到正确的 Gateway，再由 Gateway 转发给客户端 */
public class ActorNotifier {
  private final DistributedCoordinator coordinator;
  private final KafkaEventProducer eventProducer;
  private final ILogger logger = Logger.getInstance();

  public ActorNotifier(DistributedCoordinator coordinator, KafkaEventProducer eventProducer) {
    this.coordinator = coordinator;
    this.eventProducer = eventProducer;
  }

  /**
   * 发送消息给指定玩家
   *
   * @param username 玩家名
   * @param gatewayId 玩家所在 Gateway ID（null 时从 Redis 查询）
   * @param message 消息内容
   */
  public void sendToPlayer(String username, String gatewayId, String message) {
    if (username == null) return;

    // 如果没有指定 gatewayId，从 Redis 查询
    if (gatewayId == null) {
      DistributedCoordinator.PlayerLocation loc = coordinator.getPlayerLocation(username);
      if (loc == null) {
        logger.debug("Player location not found for " + username);
        return;
      }
      gatewayId = loc.gatewayId();
    }

    // 发布到玩家所在 Gateway 的频道
    coordinator.publishToGateway(gatewayId, username, message);

    if (Config.DEBUG_MESSAGE_LOGGING) {
      logger.debug("Actor sent to " + username + " via gateway " + gatewayId);
    }
  }

  public void publishPlayerDied(
      String username, int roomId, int finalScore, int finalLength, String cause) {
    if (eventProducer != null) {
      eventProducer.send(
          "game.player.died",
          new PlayerDiedEvent(username, roomId, finalScore, finalLength, cause));
    }
  }

  public void publishScoreChanged(String username, int roomId, int newScore, int delta) {
    if (eventProducer != null) {
      eventProducer.send(
          "game.player.score", new ScoreChangedEvent(username, roomId, newScore, delta));
    }
  }

  public void publishPlayerInput(String username, int roomId, snake.base.Direction direction) {
    if (eventProducer != null) {
      eventProducer.send(
          "game.player.input", new snake.event.PlayerInputEvent(username, roomId, direction));
    }
  }
}
