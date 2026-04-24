package snake.common;

public class Config {
  private static IConfigProvider provider = new PropertiesConfigProvider("config.properties");

  public static void setProvider(IConfigProvider p) {
    provider = p;
  }

  // 网络
  public static final int BUFFER_SIZE = provider.getInt("buffer.size", 8192);
  // public static final int RECV_TIMEOUT = provider.getInt("recv.timeout", 5000);
  // public static final int BACKLOG = provider.getInt("backlog", 128);
  public static final int SELECT_TIMEOUT = provider.getInt("select.timeout", 1000);

  // 主服务器
  // public static final int MAX_USERS = provider.getInt("max.users", 1024);
  // public static final int USERNAME_LEN = provider.getInt("username.len", 32);
  // public static final int THREAD_POOL_SIZE = provider.getInt("thread.pool.size", 4);
  // public static final int THREAD_QUEUE_SIZE = provider.getInt("thread.queue.size", 1024);

  // 房间
  // public static final int MAX_ROOMS = provider.getInt("max.rooms", 8);
  public static final int MAX_PLAYERS_PER_ROOM = provider.getInt("max.players.per.room", 8);
  public static final int BASE_ROOM_PORT = provider.getInt("base.room.port", 20000);
  public static final int ROOM_IDLE_TIMEOUT = provider.getInt("room.idle.timeout", 30);
  public static final int ROOM_INIT_DELAY_TICKS = provider.getInt("room.init.delay.ticks", 5);
  public static final int ROOM_QUEUE_CAPACITY = provider.getInt("room.queue.capacity", 1024);

  // 游戏
  public static final int MAP_WIDTH = provider.getInt("map.width", 40);
  public static final int MAP_HEIGHT = provider.getInt("map.height", 20);
  public static final int TICK_INTERVAL_MS = provider.getInt("tick.interval.ms", 200);
  // public static final int INIT_SNAKE_LENGTH = provider.getInt("init.snake.length", 1);
  public static final int MAX_SNAKE_LENGTH = provider.getInt("max.snake.length", 63);
  public static final int OBSTACLE_COUNT = provider.getInt("obstacle.count", 15);
  public static final int MAX_SPAWN_ATTEMPTS = provider.getInt("max.spawn.attempts", 100);

  // 网关
  public static final int GATEWAY_DEFAULT_PORT = provider.getInt("gateway.port", 19000);
  public static final int HEARTBEAT_INTERVAL = provider.getInt("heartbeat.interval", 30);
  public static final int HEARTBEAT_TIMEOUT = provider.getInt("heartbeat.timeout", 60);
  // public static final int GATEWAY_NOTIFY_PORT = provider.getInt("gateway.notify.port", 19001);
  public static final int ROOM_LIST_QUERY_PORT = provider.getInt("room.list.query.port", 19003);
  public static final int GATEWAY_ADMIN_PORT = provider.getInt("gateway.admin.port", 19004);

  // 房间服务器注册端口
  // public static final int ROOM_REGISTER_PORT = provider.getInt("room.register.port", 19002);

  // 客户端
  public static final int MAX_RETRY_ATTEMPTS = provider.getInt("max.retry.attempts", 5);
  // public static final int RETRY_DELAY_MS = provider.getInt("retry.delay.ms", 200);
}
