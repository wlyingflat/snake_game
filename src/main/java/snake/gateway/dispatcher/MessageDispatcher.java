package snake.gateway.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import snake.actor.EnhancedMessage;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;

public class MessageDispatcher {
  private final DistributedCoordinator coordinator;
  private final String localGatewayId;
  private final ILogger logger = Logger.getInstance();

  public MessageDispatcher(DistributedCoordinator coordinator, String localGatewayId) {
    this.coordinator = coordinator;
    this.localGatewayId = localGatewayId;
  }

  public void routeToWorker(String username, JsonNode msg) {
    String cmd = msg.get("cmd").asText();

    switch (cmd) {
      case "CREATE":
        routeCreate(username, msg);
        break;
      case "JOIN":
        routeJoin(username, msg);
        break;
      case "INPUT":
        routeInput(username, msg);
        break;
      case "LEAVE":
      case "QUIT":
        routeLeave(username, msg);
        break;
      default:
        logger.warn("Unknown game command: " + cmd);
    }
  }

  private void routeCreate(String username, JsonNode msg) {
    int roomId = msg.get("roomId").asInt();

    if (coordinator.roomExists(roomId)) {
      logger.info("Room " + roomId + " exists, auto-joining");
      routeJoin(username, msg);
      return;
    }

    String workerId = selectWorker(roomId);
    if (workerId == null) {
      logger.error("No active workers available");
      return;
    }

    EnhancedMessage enhancedMsg =
        new EnhancedMessage("CREATE", username, roomId, localGatewayId, msg.toString());
    coordinator.publishToWorker(workerId, enhancedMsg.toJson());
    logger.info("CREATE routed to worker " + workerId + " for room " + roomId);
  }

  private void routeJoin(String username, JsonNode msg) {
    int roomId = msg.get("roomId").asInt();

    String workerId = coordinator.getRoomWorker(roomId);
    if (workerId == null) {
      logger.warn("Room " + roomId + " not found in Redis");
      return;
    }

    coordinator.setPlayerLocation(username, localGatewayId, roomId);

    EnhancedMessage enhancedMsg =
        new EnhancedMessage("JOIN", username, roomId, localGatewayId, msg.toString());
    coordinator.publishToWorker(workerId, enhancedMsg.toJson());
  }

  private void routeInput(String username, JsonNode msg) {
    // INPUT 消息中没有 roomId，从 Redis 获取玩家位置
    DistributedCoordinator.PlayerLocation location = coordinator.getPlayerLocation(username);
    if (location == null) {
      logger.warn("Player location not found for " + username);
      return;
    }

    int roomId = location.roomId();
    if (roomId == -1) return;

    String workerId = coordinator.getRoomWorker(roomId);
    if (workerId == null) return;

    EnhancedMessage enhancedMsg =
        new EnhancedMessage("INPUT", username, roomId, localGatewayId, msg.toString());
    coordinator.publishToWorker(workerId, enhancedMsg.toJson());
  }

  private void routeLeave(String username, JsonNode msg) {
    DistributedCoordinator.PlayerLocation location = coordinator.getPlayerLocation(username);
    if (location == null) return;

    int roomId = location.roomId();
    if (roomId == -1) return;

    String workerId = coordinator.getRoomWorker(roomId);
    if (workerId == null) return;

    EnhancedMessage enhancedMsg =
        new EnhancedMessage("LEAVE", username, roomId, localGatewayId, msg.toString());
    coordinator.publishToWorker(workerId, enhancedMsg.toJson());
    coordinator.removePlayerLocation(username);
  }

  private String selectWorker(int roomId) {
    Set<String> workers = coordinator.getActiveWorkers();
    if (workers.isEmpty()) return null;

    List<String> workerList = new ArrayList<>(workers);
    String bestWorker = null;
    int minLoad = Integer.MAX_VALUE;

    for (String workerId : workerList) {
      int load = coordinator.getRoomCount(workerId);
      if (load < minLoad) {
        minLoad = load;
        bestWorker = workerId;
      }
    }

    return bestWorker != null ? bestWorker : workerList.get(0);
  }
}
