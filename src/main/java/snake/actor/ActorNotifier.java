package snake.actor;

import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;
import snake.event.KafkaEventProducer;
import snake.event.PlayerDiedEvent;
import snake.event.ScoreChangedEvent;
import snake.mq.MessageBus; // 新增

public class ActorNotifier {
  private final DistributedCoordinator coordinator;
  private final KafkaEventProducer eventProducer;
  private final MessageBus messageBus; // 新增
  private final ILogger logger = Logger.getInstance();

  public ActorNotifier(
      DistributedCoordinator coordinator, KafkaEventProducer eventProducer, MessageBus messageBus) {
    this.coordinator = coordinator;
    this.eventProducer = eventProducer;
    this.messageBus = messageBus;
  }

  public void sendToPlayer(String username, String gatewayId, String message) {
    if (username == null) return;

    if (gatewayId == null) {
      DistributedCoordinator.PlayerLocation loc = coordinator.getPlayerLocation(username);
      if (loc == null) {
        logger.debug("Player location not found for " + username);
        return;
      }
      gatewayId = loc.gatewayId();
    }

    // 优先使用 RabbitMQ（如果可用），否则降级到 Redis
    if (messageBus != null) {
      messageBus.publishToPlayer(gatewayId, username, message);
    } else {
      coordinator.publishToGateway(gatewayId, username, message);
    }

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
