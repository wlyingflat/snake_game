package snake.distributed;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import snake.base.Config;

/** 负责玩家与 Gateway/房间的位置映射。 */
public class PlayerLocationRepository {
  private final RedissonClient redisson;
  private final String nodeId; // 用于 set 时记录所属节点

  public PlayerLocationRepository(RedissonClient redisson, String nodeId) {
    this.redisson = redisson;
    this.nodeId = nodeId;
  }

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

  /** 玩家位置记录（与原始 record 一致）。 */
  public record PlayerLocation(String gatewayId, int roomId) {}
}
