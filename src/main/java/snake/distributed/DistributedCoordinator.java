package snake.distributed;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.redisson.api.*;
import org.redisson.api.listener.MessageListener;
import org.redisson.api.listener.PatternMessageListener;
import snake.base.*;

public class DistributedCoordinator {
  private final RedissonClient redisson;
  private final String nodeId;
  private final ILogger logger = Logger.getInstance();

  public DistributedCoordinator(RedissonClient redisson, String nodeId) {
    this.redisson = redisson;
    this.nodeId = nodeId;
  }

  public String getNodeId() {
    return nodeId;
  }

  // ==================== 房间管理 ====================

  public boolean tryCreateRoom(int roomId, int maxPlayers) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    boolean created = roomMap.fastPutIfAbsent("nodeId", nodeId);
    if (created) {
      roomMap.fastPut("status", "OPEN");
      roomMap.fastPut("playerCount", 0);
      roomMap.fastPut("maxPlayers", maxPlayers);
      roomMap.expire(2, TimeUnit.HOURS);
      logger.info("Room " + roomId + " registered in Redis by node " + nodeId);
    }
    return created;
  }

  public void updateRoomInfo(int roomId, int playerCount, boolean isFull) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    roomMap.fastPut("playerCount", playerCount);
    roomMap.fastPut("status", isFull ? "FULL" : "OPEN");
    roomMap.expire(2, TimeUnit.HOURS);
  }

  public void deleteRoom(int roomId) {
    redisson.getMap(RedisKeys.ROOM_PREFIX + roomId).delete();
    logger.info("Room " + roomId + " removed from Redis");
  }

  public boolean roomExists(int roomId) {
    return redisson.getMap(RedisKeys.ROOM_PREFIX + roomId).isExists();
  }

  public List<RoomEntry> getAllRooms() {
    List<RoomEntry> rooms = new ArrayList<>();
    Iterable<String> keys = redisson.getKeys().getKeysByPattern(RedisKeys.ROOM_PREFIX + "*");
    for (String key : keys) {
      RMap<String, Object> map = redisson.getMap(key);
      if (map.isEmpty()) continue;
      int roomId = Integer.parseInt(key.substring(RedisKeys.ROOM_PREFIX.length()));
      rooms.add(
          new RoomEntry(
              roomId,
              (String) map.get("status"),
              ((Number) map.getOrDefault("playerCount", 0)).intValue(),
              ((Number) map.getOrDefault("maxPlayers", 0)).intValue(),
              (String) map.get("nodeId")));
    }
    return rooms;
  }

  public record RoomEntry(
      int roomId, String status, int playerCount, int maxPlayers, String nodeId) {}

  // ==================== Worker 管理 ====================

  public void registerWorker(String workerId) {
    RSet<String> workers = redisson.getSet(RedisKeys.WORKER_NODES);
    workers.add(workerId);
    logger.info("Worker registered: " + workerId);
  }

  public void unregisterWorker(String workerId) {
    RSet<String> workers = redisson.getSet(RedisKeys.WORKER_NODES);
    workers.remove(workerId);
    logger.info("Worker unregistered: " + workerId);
  }

  public Set<String> getActiveWorkers() {
    Set<String> workers = new HashSet<>();
    for (Object obj : redisson.getSet(RedisKeys.WORKER_NODES).readAll()) {
      workers.add(obj.toString());
    }
    return workers;
  }

  // ==================== Gateway 管理 ====================

  public void registerGateway(String gatewayId) {
    redisson.getSet(RedisKeys.GATEWAY_NODES).add(gatewayId);
    logger.info("Gateway registered: " + gatewayId);
  }

  public void unregisterGateway(String gatewayId) {
    redisson.getSet(RedisKeys.GATEWAY_NODES).remove(gatewayId);
    logger.info("Gateway unregistered: " + gatewayId);
  }

  // ==================== 房间到 Worker 的映射 ====================

  public boolean assignRoomToWorker(int roomId, String workerId) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    roomMap.fastPut("workerId", workerId);
    return true;
  }

  public String getRoomWorker(int roomId) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    return (String) roomMap.get("workerId");
  }

  public int getRoomCount(String workerId) {
    int count = 0;
    Iterable<String> keys = redisson.getKeys().getKeysByPattern(RedisKeys.ROOM_PREFIX + "*");
    for (String key : keys) {
      RMap<String, Object> map = redisson.getMap(key);
      if (workerId.equals(map.get("workerId"))) {
        count++;
      }
    }
    return count;
  }

  // ==================== 玩家位置管理 ====================

  public void setPlayerLocation(String username, String gatewayId, int roomId) {
    RMap<String, Object> playerMap = redisson.getMap(RedisKeys.PLAYER_PREFIX + username);
    playerMap.fastPut("gatewayId", gatewayId);
    playerMap.fastPut("roomId", roomId);
    playerMap.fastPut("nodeId", nodeId);
    playerMap.expire(Config.HEARTBEAT_TIMEOUT * 2, TimeUnit.SECONDS);
  }

  public PlayerLocation getPlayerLocation(String username) {
    RMap<String, Object> map = redisson.getMap(RedisKeys.PLAYER_PREFIX + username);
    if (map.isEmpty()) return null;
    return new PlayerLocation(
        (String) map.get("gatewayId"), ((Number) map.getOrDefault("roomId", -1)).intValue());
  }

  public void removePlayerLocation(String username) {
    redisson.getMap(RedisKeys.PLAYER_PREFIX + username).delete();
  }

  public void refreshPlayerLocation(String username) {
    if (username == null) return;
    RMap<String, Object> playerMap = redisson.getMap(RedisKeys.PLAYER_PREFIX + username);
    if (!playerMap.isEmpty()) {
      playerMap.expire(Config.HEARTBEAT_TIMEOUT * 2, TimeUnit.SECONDS);
    }
  }

  public record PlayerLocation(String gatewayId, int roomId) {}

  // ==================== 在线状态管理 ====================

  public void markOnline(String username) {
    if (username == null) return;
    RMap<String, Long> onlineMap = redisson.getMap(RedisKeys.ONLINE_USERS_MAP);
    onlineMap.fastPut(username, System.currentTimeMillis());
    onlineMap.expire(Config.HEARTBEAT_TIMEOUT * 2, TimeUnit.SECONDS);
    redisson.getSet(RedisKeys.ONLINE_USERS).add(username);
  }

  public void refreshOnline(String username) {
    if (username == null) return;
    RMap<String, Long> onlineMap = redisson.getMap(RedisKeys.ONLINE_USERS_MAP);
    Long lastSeen = onlineMap.get(username);
    if (lastSeen != null) {
      onlineMap.fastPut(username, System.currentTimeMillis());
      onlineMap.expire(Config.HEARTBEAT_TIMEOUT * 2, TimeUnit.SECONDS);
    }
  }

  public void markOffline(String username) {
    if (username == null) return;
    RMap<String, Long> onlineMap = redisson.getMap(RedisKeys.ONLINE_USERS_MAP);
    onlineMap.remove(username);
    redisson.getSet(RedisKeys.ONLINE_USERS).remove(username);
  }

  public boolean isOnline(String username) {
    if (username == null) return false;
    RMap<String, Long> onlineMap = redisson.getMap(RedisKeys.ONLINE_USERS_MAP);
    Long lastSeen = onlineMap.get(username);
    if (lastSeen == null) {
      boolean inOldSet = redisson.getSet(RedisKeys.ONLINE_USERS).contains(username);
      if (inOldSet) {
        onlineMap.fastPut(username, System.currentTimeMillis());
        onlineMap.expire(Config.HEARTBEAT_TIMEOUT * 2, TimeUnit.SECONDS);
        return true;
      }
      return false;
    }
    long elapsed = System.currentTimeMillis() - lastSeen;
    if (elapsed > Config.HEARTBEAT_TIMEOUT * 2 * 1000L) {
      logger.warn("User " + username + " online status expired");
      markOffline(username);
      return false;
    }
    return true;
  }

  // ==================== 消息发布/订阅 ====================

  public void publishToWorker(String workerId, String message) {
    redisson.getTopic(RedisKeys.WORKER_CHANNEL_PREFIX + workerId).publish(message);
  }

  public int subscribeWorkerMessages(String workerId, MessageListener<String> listener) {
    return redisson
        .getTopic(RedisKeys.WORKER_CHANNEL_PREFIX + workerId)
        .addListener(String.class, listener);
  }

  public void unsubscribeWorkerMessages(String workerId, int listenerId) {
    redisson.getTopic(RedisKeys.WORKER_CHANNEL_PREFIX + workerId).removeListener(listenerId);
  }

  public void publishToGateway(String gatewayId, String username, String message) {
    String channel = String.format(RedisKeys.GATEWAY_PLAYER_CHANNEL, gatewayId, username);
    redisson.getTopic(channel).publish(message);
  }

  public int subscribeGatewayMessages(String gatewayId, PatternMessageListener<String> listener) {
    String pattern = String.format(RedisKeys.GATEWAY_PLAYER_CHANNEL, gatewayId, "*");
    return redisson.getPatternTopic(pattern).addListener(String.class, listener);
  }

  public void publishRoomListUpdate() {
    redisson.getTopic(RedisKeys.ROOM_LIST_UPDATE_CHANNEL).publish("update");
  }

  public int subscribeRoomListUpdates(MessageListener<String> listener) {
    return redisson
        .getTopic(RedisKeys.ROOM_LIST_UPDATE_CHANNEL)
        .addListener(String.class, listener);
  }

  public void publishToRoom(int roomId, String message) {
    redisson.getTopic(String.format(RedisKeys.ROOM_BROADCAST_CHANNEL, roomId)).publish(message);
  }

  public void publishToPlayer(String username, String message) {
    redisson.getTopic(String.format(RedisKeys.PLAYER_DIRECT_CHANNEL, username)).publish(message);
  }

  public int subscribeDirectMessages(PatternMessageListener<String> listener) {
    return redisson
        .getPatternTopic(RedisKeys.PLAYER_DIRECT_CHANNEL.replace("%s", "*"))
        .addListener(String.class, listener);
  }

  // ==================== 排行榜 ====================

  public void updateHighScore(String username, int score) {
    RScoredSortedSet<String> leaderboard =
        redisson.getScoredSortedSet(
            RedisKeys.LEADERBOARD_KEY, org.redisson.client.codec.StringCodec.INSTANCE);
    Double current = leaderboard.getScore(username);
    if (current == null || current < score) {
      leaderboard.add(score, username);
    }
  }

  public List<UserRank> getLeaderboard(int limit) {
    RScoredSortedSet<String> leaderboard =
        redisson.getScoredSortedSet(
            RedisKeys.LEADERBOARD_KEY, org.redisson.client.codec.StringCodec.INSTANCE);
    List<UserRank> ranks = new ArrayList<>();
    Collection<String> usernames = leaderboard.valueRangeReversed(0, limit - 1);
    if (usernames != null) {
      int rank = 1;
      for (String username : usernames) {
        Double score = leaderboard.getScore(username);
        if (score != null) {
          ranks.add(new UserRank(rank++, username, score.intValue()));
        }
      }
    }
    return ranks;
  }

  // 兼容旧代码
  public void registerNode() {
    registerGateway(nodeId);
  }

  public void unregisterNode() {
    unregisterGateway(nodeId);
  }

  public void refreshNode() {
    // no-op for now
  }

  public static class UserRank {
    public final int rank;
    public final String username;
    public final int score;

    public UserRank(int rank, String username, int score) {
      this.rank = rank;
      this.username = username;
      this.score = score;
    }
  }
}
