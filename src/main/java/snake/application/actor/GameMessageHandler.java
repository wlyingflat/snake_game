package snake.application.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import snake.common.*;
import snake.common.Serializer;
import snake.distributed.DistributedCoordinator;
import snake.domain.game.GameState;

/** 处理来自客户端的游戏命令（JOIN, INPUT, LEAVE）， 与 GameState 交互，但不负责 tick 逻辑。 */
public class GameMessageHandler {
  private final int roomId;
  private final GameState state;
  private final ActorNotifier notifier;
  private final DistributedCoordinator coordinator;
  private final GameTickProcessor tickProcessor;
  private final Runnable onStatusChange;
  private final ILogger logger = Logger.getInstance();

  public GameMessageHandler(
      int roomId,
      GameState state,
      ActorNotifier notifier,
      DistributedCoordinator coordinator,
      GameTickProcessor tickProcessor,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.state = state;
    this.notifier = notifier;
    this.coordinator = coordinator;
    this.tickProcessor = tickProcessor;
    this.onStatusChange = onStatusChange;
  }

  public void handle(EnhancedMessage msg) {
    switch (msg.getCommand()) {
      case "CREATE":
      case "JOIN":
        handleJoin(msg.getUsername(), msg.getGatewayId());
        break;
      case "INPUT":
        handleInput(msg);
        break;
      case "LEAVE":
        handleLeave(msg.getUsername(), msg.getGatewayId());
        break;
      default:
        logger.warn("Actor " + roomId + " unknown command: " + msg.getCommand());
    }
  }

  private void handleJoin(String username, String gatewayId) {
    boolean joined = state.addPlayer(username);
    if (joined) {
      String joinOk = buildJoinOkMessage();
      notifier.sendToPlayer(username, gatewayId, joinOk);
      if (gatewayId != null) {
        coordinator.setPlayerLocation(username, gatewayId, roomId);
      }
      sendFullStateToPlayer(username, gatewayId);
      tickProcessor.refreshCache();
      // 新玩家需要全量快照，由上层在适当时候发送（此处仅通知，实际发送由 tick 逻辑负责）
      logger.info("Player " + username + " joined actor " + roomId);
      if (onStatusChange != null) onStatusChange.run();
    } else {
      String joinFail = buildJoinFailMessage();
      notifier.sendToPlayer(username, gatewayId, joinFail);
      logger.warn("Player " + username + " failed to join actor " + roomId);
    }
  }

  private void sendFullStateToPlayer(String username, String gatewayId) {
    GameStateData snapshot = state.snapshot(username);
    String json = new Serializer().serialize(snapshot);
    if (json != null) {
      notifier.sendToPlayer(username, gatewayId, json);
    }
  }

  private void handleInput(EnhancedMessage msg) {
    try {
      JsonNode params = JsonUtils.MAPPER.readTree(msg.getRawMessage());
      Direction dir = Direction.valueOf(params.get("direction").asText());
      state.updateDirection(msg.getUsername(), dir);
    } catch (Exception e) {
      logger.error("Failed to parse input: " + e.getMessage());
    }
  }

  private void handleLeave(String username, String gatewayId) {
    state.removePlayer(username);
    String leaveMsg = "{\"cmd\":\"YOU_LEFT\"}";
    notifier.sendToPlayer(username, gatewayId, leaveMsg);
    coordinator.removePlayerLocation(username);
    logger.info("Player " + username + " left actor " + roomId);
    if (onStatusChange != null) onStatusChange.run();

    // 立即广播最新状态，与原始行为一致
    tickProcessor.refreshCache();
    broadcastCurrentState();
  }

  private void broadcastCurrentState() {
    GameStateData snapshot = state.snapshot(null);
    if (snapshot == null) return;
    String json = new Serializer().serialize(snapshot);
    if (json == null) return;
    for (GameState.Player p : state.getPlayers()) {
      if (!p.isDead) {
        notifier.sendToPlayer(p.username, null, json);
      }
    }
  }

  private String buildJoinOkMessage() {
    // 使用 JsonUtils 保证合法 JSON
    ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "JOIN_OK");
    resp.put("roomId", roomId);
    try {
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String buildJoinFailMessage() {
    ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "JOIN_FAIL");
    resp.put("message", "Room is full or join failed");
    try {
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{}";
    }
  }
}
