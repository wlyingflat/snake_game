package snake.distributed;

public final class RedisKeys {
  private RedisKeys() {}

  // 房间元数据 Hash: room:{roomId}
  public static final String ROOM_PREFIX = "room:";

  // 玩家位置 Hash: player:{username}
  public static final String PLAYER_PREFIX = "player:";

  // 在线用户 Hash (带时间戳): online_users_map
  public static final String ONLINE_USERS_MAP = "online_users_map";

  // 在线用户集合 (Set) - 兼容过渡
  @Deprecated public static final String ONLINE_USERS = "online_users";

  // 节点注册
  public static final String ACTIVE_NODES = "active_nodes";
  public static final String WORKER_NODES = "worker_nodes";
  public static final String GATEWAY_NODES = "gateway_nodes";

  // 消息频道
  public static final String WORKER_CHANNEL_PREFIX = "worker_channel:";
  public static final String GATEWAY_PLAYER_CHANNEL = "gateway:%s:player:%s";
  public static final String ROOM_BROADCAST_CHANNEL = "room_broadcast:%d";
  public static final String PLAYER_DIRECT_CHANNEL = "player_direct:%s";
  public static final String ROOM_LIST_UPDATE_CHANNEL = "room_list_update";

  // 排行榜
  public static final String LEADERBOARD_KEY = "leaderboard";
}
