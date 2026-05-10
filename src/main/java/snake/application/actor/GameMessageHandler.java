package snake.application.actor;

import com.fasterxml.jackson.databind.JsonNode;
import snake.common.*;
import snake.distributed.DistributedCoordinator;
import snake.domain.game.AgarGameState;

public class GameMessageHandler {
  private final int roomId;
  private final AgarGameState state;
  private final ActorNotifier notifier;
  private final DistributedCoordinator coordinator;
  private final Runnable onStatusChange;
  private final ILogger logger = Logger.getInstance();

  public GameMessageHandler(
      int roomId,
      AgarGameState state,
      ActorNotifier notifier,
      DistributedCoordinator coordinator,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.state = state;
    this.notifier = notifier;
    this.coordinator = coordinator;
    this.onStatusChange = onStatusChange;
  }

  public void handle(EnhancedMessage msg) {
    switch (msg.getCommand()) {
      case "JOIN":
        handleJoin(msg.getUsername(), msg.getGatewayId());
        break;
      case "MOVE":
        handleMove(msg);
        break;
      case "SPLIT":
        handleSplit(msg);
        break;
      case "EJECT":
        handleEject(msg);
        break;
      case "LEAVE":
        handleLeave(msg.getUsername(), msg.getGatewayId());
        break;
      default:
        logger.warn("Unknown agar command: " + msg.getCommand());
    }
  }

  private void handleJoin(String username, String gatewayId) {
    if (state.addPlayer(username)) {
      notifier.sendToPlayer(username, gatewayId, "{\"cmd\":\"JOIN_OK\",\"roomId\":" + roomId + "}");
      if (gatewayId != null && coordinator != null)
        coordinator.setPlayerLocation(username, gatewayId, roomId);
      if (onStatusChange != null) onStatusChange.run();
    } else {
      notifier.sendToPlayer(
          username, gatewayId, "{\"cmd\":\"JOIN_FAIL\",\"message\":\"Room full\"}");
    }
  }

  private void handleMove(EnhancedMessage msg) {
    try {
      JsonNode p = JsonUtils.MAPPER.readTree(msg.getRawMessage());
      state.updateTarget(msg.getUsername(), p.get("x").floatValue(), p.get("y").floatValue());
    } catch (Exception e) {
      logger.error("MOVE parse error");
    }
  }

  private void handleSplit(EnhancedMessage msg) {
    try {
      JsonNode p = JsonUtils.MAPPER.readTree(msg.getRawMessage());
      state.splitPlayer(msg.getUsername(), p.get("x").floatValue(), p.get("y").floatValue());
    } catch (Exception e) {
      logger.error("SPLIT parse error");
    }
  }

  private void handleEject(EnhancedMessage msg) {
    try {
      JsonNode p = JsonUtils.MAPPER.readTree(msg.getRawMessage());
      state.ejectMass(msg.getUsername(), p.get("x").floatValue(), p.get("y").floatValue());
    } catch (Exception e) {
      logger.error("EJECT parse error");
    }
  }

  private void handleLeave(String username, String gatewayId) {
    state.removePlayer(username);
    notifier.sendToPlayer(username, gatewayId, "{\"cmd\":\"YOU_LEFT\"}");
    if (coordinator != null) coordinator.removePlayerLocation(username);
    if (onStatusChange != null) onStatusChange.run();
  }
}
