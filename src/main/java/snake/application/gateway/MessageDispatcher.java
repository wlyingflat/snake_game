package snake.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import snake.application.actor.EnhancedMessage;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.messaging.MessageBus;

public class MessageDispatcher {
  private final DistributedCoordinator coordinator;
  private final String localGatewayId;
  private final MessageBus messageBus;
  private final ILogger logger = Logger.getInstance();

  public MessageDispatcher(
      DistributedCoordinator coordinator, MessageBus messageBus, String localGatewayId) {
    this.coordinator = coordinator;
    this.messageBus = messageBus;
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
      // 新增处理吞噬游戏的移动、分裂、弹出命令
      case "INPUT":
      case "MOVE":
      case "SPLIT":
      case "EJECT":
        routeGameAction(username, msg, cmd);
        break;
      case "LEAVE":
      case "QUIT":
        routeLeave(username, msg);
        break;
      default:
        logger.warn("Unknown game command: " + cmd);
    }
  }

  // 提取公共方法：根据玩家定位到Worker并转发游戏指令
  private void routeGameAction(String username, JsonNode msg, String cmd) {
    DistributedCoordinator.PlayerLocation location = coordinator.getPlayerLocation(username);
    if (location == null) {
      logger.warn("No location for " + username + ", cannot route " + cmd);
      return;
    }
    int roomId = location.roomId();
    if (roomId == -1) return;
    String workerId = coordinator.getRoomWorker(roomId);
    if (workerId == null) {
      logger.warn("No worker for room " + roomId + ", cannot route " + cmd);
      return;
    }
    EnhancedMessage enhancedMsg =
        EnhancedMessage.newInstance().init(cmd, username, roomId, localGatewayId, msg.toString());
    try {
      messageBus.sendToWorker(workerId, enhancedMsg.toProtobuf());
    } finally {
      enhancedMsg.recycle();
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
        EnhancedMessage.newInstance()
            .init("CREATE", username, roomId, localGatewayId, msg.toString());
    try {
      messageBus.sendToWorker(workerId, enhancedMsg.toProtobuf());
    } finally {
      enhancedMsg.recycle();
    }
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
        EnhancedMessage.newInstance()
            .init("JOIN", username, roomId, localGatewayId, msg.toString());
    try {
      messageBus.sendToWorker(workerId, enhancedMsg.toProtobuf());
    } finally {
      enhancedMsg.recycle();
    }
  }

  private void routeLeave(String username, JsonNode msg) {
    DistributedCoordinator.PlayerLocation location = coordinator.getPlayerLocation(username);
    if (location == null) return;
    int roomId = location.roomId();
    if (roomId == -1) return;
    String workerId = coordinator.getRoomWorker(roomId);
    if (workerId == null) return;
    EnhancedMessage enhancedMsg =
        EnhancedMessage.newInstance()
            .init("LEAVE", username, roomId, localGatewayId, msg.toString());
    try {
      messageBus.sendToWorker(workerId, enhancedMsg.toProtobuf());
    } finally {
      enhancedMsg.recycle();
    }
    coordinator.removePlayerLocation(username);
  }

  private String selectWorker(int roomId) {
    Set<String> workers = coordinator.getActiveWorkers();
    if (workers.isEmpty()) return null;
    List<String> workerList = new ArrayList<>(workers);
    String bestWorker = null;
    int minLoad = Integer.MAX_VALUE;
    for (String wid : workerList) {
      int load = coordinator.getRoomCount(wid);
      if (load < minLoad) {
        minLoad = load;
        bestWorker = wid;
      }
    }
    return bestWorker != null ? bestWorker : workerList.get(0);
  }
}
