package snake.base;

import snake.persistence.PropertiesConfigProvider;

public class Config {
  private static IConfigProvider provider = new PropertiesConfigProvider("config.properties");

  public static void setProvider(IConfigProvider p) {
    provider = p;
  }

  public static final boolean DEBUG_MESSAGE_LOGGING =
      Boolean.parseBoolean(System.getProperty("debug.message", "false"));

  // ==================== 网络基础 ====================
  public static final int BUFFER_SIZE = provider.getInt("buffer.size", 8192);
  public static final int SELECT_TIMEOUT = provider.getInt("select.timeout", 1000);

  // ==================== 房间相关 ====================
  public static final int MAX_PLAYERS_PER_ROOM = provider.getInt("max.players.per.room", 8);
  public static final int ROOM_IDLE_TIMEOUT = provider.getInt("room.idle.timeout", 30);
  public static final int ROOM_INIT_DELAY_TICKS = provider.getInt("room.init.delay.ticks", 5);
  public static final int ROOM_QUEUE_CAPACITY = provider.getInt("room.queue.capacity", 1024);

  // ==================== 游戏参数 ====================
  public static final int MAP_WIDTH = provider.getInt("map.width", 40);
  public static final int MAP_HEIGHT = provider.getInt("map.height", 20);
  public static final int TICK_INTERVAL_MS = provider.getInt("tick.interval.ms", 200);
  public static final int MAX_SNAKE_LENGTH = provider.getInt("max.snake.length", 63);
  public static final int OBSTACLE_COUNT = provider.getInt("obstacle.count", 15);
  public static final int MAX_SPAWN_ATTEMPTS = provider.getInt("max.spawn.attempts", 100);

  // ==================== 认证服务（HTTP） ====================
  public static final int AUTH_SERVICE_PORT = provider.getInt("auth.service.port", 19001);
  public static final String AUTH_SERVICE_URL =
      provider.getString("auth.service.url", "http://127.0.0.1:" + AUTH_SERVICE_PORT);

  // ==================== 网关 ====================
  public static final String GATEWAY_HOST = provider.getString("gateway.host", "127.0.0.1");
  public static final int GATEWAY_PORT = provider.getInt("gateway.port", 8080);
  public static final int GATEWAY_DEFAULT_PORT = provider.getInt("gateway.default.port", 8080);
  public static final int HEARTBEAT_INTERVAL = provider.getInt("heartbeat.interval", 30);
  public static final int HEARTBEAT_TIMEOUT = provider.getInt("heartbeat.timeout", 60);

  // 网关辅助服务端口
  public static final int ROOM_LIST_QUERY_PORT = provider.getInt("room.list.query.port", 19003);
  public static final int GATEWAY_ADMIN_PORT = provider.getInt("gateway.admin.port", 19004);

  // ==================== Disruptor ====================
  public static final int RING_BUFFER_SIZE = provider.getInt("ring.buffer.size", 1024);

  // ==================== 客户端 ====================
  public static final int MAX_RETRY_ATTEMPTS = provider.getInt("max.retry.attempts", 5);
}
