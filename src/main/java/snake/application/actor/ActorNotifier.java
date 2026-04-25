package snake.application.actor;

import snake.common.Config;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.event.KafkaEventProducer;
import snake.infrastructure.event.PlayerDiedEvent;
import snake.infrastructure.event.ScoreChangedEvent;
import snake.infrastructure.messaging.MessageBus;

public class ActorNotifier {
  private final DistributedCoordinator coordinator;
  private final KafkaEventProducer eventProducer;
  private final MessageBus messageBus;
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

    if (messageBus != null) {
      messageBus.publishToPlayer(gatewayId, username, message);
    } else {
      logger.warn("MessageBus not available, cannot send message to " + username);
    }

    if (Config.DEBUG_MESSAGE_LOGGING) {
      logger.debug("Actor sent to " + username + " via gateway " + gatewayId);
    }
  }

  public void publishPlayerDied(
      String username, int roomId, int finalScore, int finalLength, String cause) {
    if (eventProducer == null) return;
    PlayerDiedEvent event =
        PlayerDiedEvent.newInstance().init(username, roomId, finalScore, finalLength, cause);
    try {
      eventProducer.send("game.player.died", event);
    } finally {
      event.recycle(); // toJson 已在 send 内同步调用，可以立即回收
    }
  }

  public void publishScoreChanged(String username, int roomId, int newScore, int delta) {
    if (eventProducer == null) return;
    ScoreChangedEvent event =
        ScoreChangedEvent.newInstance().init(username, roomId, newScore, delta);
    try {
      eventProducer.send("game.player.score", event);
    } finally {
      event.recycle();
    }
  }
}
