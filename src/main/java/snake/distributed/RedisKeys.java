package snake.distributed;

public final class RedisKeys {
  private RedisKeys() {}

  // 房间元数据 Hash: room:{roomId}
  public static final String ROOM_PREFIX = "room:";

  // 玩家位置 Hash: player:{username}
  public static final String PLAYER_PREFIX = "player:";

  // 在线用户 Hash (带时间戳): online_users_map
  public static final String ONLINE_USERS_MAP = "online_users_map";

  // 节点注册
  public static final String WORKER_NODES = "worker_nodes";
  public static final String GATEWAY_NODES = "gateway_nodes";

  // 排行榜
  public static final String LEADERBOARD_KEY = "leaderboard";
}
