package snake.distributed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.RedisKeys;

/** 负责房间在 Redis 中的创建、更新、删除、查询，以及 Worker 分配。 */
public class RoomRepository {
  private final RedissonClient redisson;
  private final ILogger logger = Logger.getInstance();

  public RoomRepository(RedissonClient redisson) {
    this.redisson = redisson;
  }

  /** 尝试创建房间（原子操作），成功返回 true。 */
  public boolean tryCreateRoom(int roomId, int maxPlayers, String nodeId) {
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

  /** 更新房间状态信息。 */
  public void updateRoomInfo(int roomId, int playerCount, boolean isFull) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    roomMap.fastPut("playerCount", playerCount);
    roomMap.fastPut("status", isFull ? "FULL" : "OPEN");
    roomMap.expire(2, TimeUnit.HOURS);
  }

  /** 删除房间。 */
  public void deleteRoom(int roomId) {
    redisson.getMap(RedisKeys.ROOM_PREFIX + roomId).delete();
    logger.info("Room " + roomId + " removed from Redis");
  }

  /** 检查房间是否已存在。 */
  public boolean roomExists(int roomId) {
    return redisson.getMap(RedisKeys.ROOM_PREFIX + roomId).isExists();
  }

  /** 获取所有房间信息列表。 */
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

  /** 分配 Worker 给房间。 */
  public void assignRoomToWorker(int roomId, String workerId) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    roomMap.fastPut("workerId", workerId);
  }

  /** 获取负责该房间的 Worker ID。 */
  public String getRoomWorker(int roomId) {
    RMap<String, Object> roomMap = redisson.getMap(RedisKeys.ROOM_PREFIX + roomId);
    return (String) roomMap.get("workerId");
  }

  /** 统计指定 Worker 上的房间数量。 */
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

  /** 房间条目（与原始内部 record 一致）。 */
  public static class RoomEntry {
    private final int roomId;
    private final String status;
    private final int playerCount;
    private final int maxPlayers;
    private final String nodeId;

    public RoomEntry(int roomId, String status, int playerCount, int maxPlayers, String nodeId) {
      this.roomId = roomId;
      this.status = status;
      this.playerCount = playerCount;
      this.maxPlayers = maxPlayers;
      this.nodeId = nodeId;
    }

    public int roomId() {
      return roomId;
    }

    public String status() {
      return status;
    }

    public int playerCount() {
      return playerCount;
    }

    public int maxPlayers() {
      return maxPlayers;
    }

    public String nodeId() {
      return nodeId;
    }
  }
}
