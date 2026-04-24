package snake.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import snake.util.Logger;

public class Config {
  private static final Properties props = new Properties();

  static {
    // 尝试加载 config.properties
    try (InputStream input = new FileInputStream("config.properties")) {
      props.load(input);
      Logger.info("Loaded config from config.properties");
    } catch (IOException e) {
      Logger.warn("config.properties not found, using built-in defaults");
    }
  }

  private static int getInt(String key, int defaultValue) {
    String val = props.getProperty(key);
    if (val != null) {
      try {
        return Integer.parseInt(val);
      } catch (NumberFormatException e) {
        Logger.warn("Invalid integer for " + key + ", using default");
      }
    }
    return defaultValue;
  }

  private static String getString(String key, String defaultValue) {
    return props.getProperty(key, defaultValue);
  }

  // 网络
  public static final int BUFFER_SIZE = getInt("buffer.size", 8192);
  public static final int RECV_TIMEOUT = getInt("recv.timeout", 5000);
  public static final int BACKLOG = getInt("backlog", 128);
  public static final int SELECT_TIMEOUT = getInt("select.timeout", 1000);

  // 主服务器
  public static final int MAX_USERS = getInt("max.users", 1024);
  public static final int USERNAME_LEN = getInt("username.len", 32);
  public static final int THREAD_POOL_SIZE = getInt("thread.pool.size", 4);
  public static final int THREAD_QUEUE_SIZE = getInt("thread.queue.size", 1024);

  // 房间
  public static final int MAX_ROOMS = getInt("max.rooms", 8);
  public static final int MAX_PLAYERS_PER_ROOM = getInt("max.players.per.room", 8);
  public static final int BASE_ROOM_PORT = getInt("base.room.port", 20000);
  public static final int ROOM_IDLE_TIMEOUT = getInt("room.idle.timeout", 30);
  public static final int ROOM_INIT_DELAY_TICKS = getInt("room.init.delay.ticks", 5);

  // 游戏
  public static final int MAP_WIDTH = getInt("map.width", 40);
  public static final int MAP_HEIGHT = getInt("map.height", 20);
  public static final int TICK_INTERVAL_MS = getInt("tick.interval.ms", 200);
  public static final int INIT_SNAKE_LENGTH = getInt("init.snake.length", 1);
  public static final int MAX_SNAKE_LENGTH = getInt("max.snake.length", 63);
  public static final int OBSTACLE_COUNT = getInt("obstacle.count", 15);
  public static final int MAX_SPAWN_ATTEMPTS = getInt("max.spawn.attempts", 100);

  // 网关
  public static final int GATEWAY_DEFAULT_PORT = getInt("gateway.port", 19000);
  public static final int HEARTBEAT_INTERVAL = getInt("heartbeat.interval", 30);
  public static final int HEARTBEAT_TIMEOUT = getInt("heartbeat.timeout", 60);
  public static final int GATEWAY_NOTIFY_PORT = getInt("gateway.notify.port", 19001);
  public static final int ROOM_LIST_QUERY_PORT = getInt("room.list.query.port", 19003);
  public static final int GATEWAY_ADMIN_PORT = getInt("gateway.admin.port", 19004); // 新增

  // 房间服务器注册端口
  public static final int ROOM_REGISTER_PORT = getInt("room.register.port", 19002);

  // 客户端
  public static final int MAX_RETRY_ATTEMPTS = getInt("max.retry.attempts", 5);
  public static final int RETRY_DELAY_MS = getInt("retry.delay.ms", 200);
}
