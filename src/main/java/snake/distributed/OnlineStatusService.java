package snake.distributed;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import snake.common.Config;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.RedisKeys;

/** 负责在线状态管理，包含超时检查和心跳刷新。 */
public class OnlineStatusService {
  private final RedissonClient redisson;
  private final ILogger logger = Logger.getInstance();

  public OnlineStatusService(RedissonClient redisson) {
    this.redisson = redisson;
  }

  public void markOnline(String username) {
    if (username == null) return;
    RMap<String, Long> onlineMap = redisson.getMap(RedisKeys.ONLINE_USERS_MAP);
    onlineMap.fastPut(username, System.currentTimeMillis());
    onlineMap.expire(Config.HEARTBEAT_TIMEOUT * 2, TimeUnit.SECONDS);
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
  }

  public boolean isOnline(String username) {
    if (username == null) return false;
    RMap<String, Long> onlineMap = redisson.getMap(RedisKeys.ONLINE_USERS_MAP);
    Long lastSeen = onlineMap.get(username);
    if (lastSeen == null) {
      return false;
    }
    long elapsed = System.currentTimeMillis() - lastSeen;
    if (elapsed > Config.HEARTBEAT_TIMEOUT * 2 * 1000L) {
      logger.warn("User " + username + " online status expired");
      markOffline(username); // 清除过期记录
      return false;
    }
    return true;
  }
}
