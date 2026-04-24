package snake.common;

public class Config {
  // 网络
  public static final int BUFFER_SIZE = 8192;
  public static final int RECV_TIMEOUT = 5000; // ms
  public static final int BACKLOG = 128;
  public static final int SELECT_TIMEOUT = 1000; // ms

  // 主服务器
  public static final int MAX_USERS = 1024;
  public static final int USERNAME_LEN = 32;
  public static final int THREAD_POOL_SIZE = 4;
  public static final int THREAD_QUEUE_SIZE = 1024;

  // 房间
  public static final int MAX_ROOMS = 8;
  public static final int MAX_PLAYERS_PER_ROOM = 8;
  public static final int BASE_ROOM_PORT = 20000;
  public static final int ROOM_IDLE_TIMEOUT = 30; // seconds
  public static final int ROOM_INIT_DELAY_TICKS = 5; // ticks

  // 游戏
  public static final int MAP_WIDTH = 40;
  public static final int MAP_HEIGHT = 20;
  public static final int TICK_INTERVAL_MS = 200;
  public static final int INIT_SNAKE_LENGTH = 1;
  public static final int MAX_SNAKE_LENGTH = 63;
  public static final int OBSTACLE_COUNT = 15;
  public static final int MAX_SPAWN_ATTEMPTS = 100;

  // 网关
  public static final int GATEWAY_DEFAULT_PORT = 19000;
  public static final int HEARTBEAT_INTERVAL = 30; // seconds
  public static final int HEARTBEAT_TIMEOUT = 60; // seconds
  public static final int GATEWAY_NOTIFY_PORT = 19001; // 主服务器 -> 网关通知端口
  public static final int ROOM_LIST_QUERY_PORT = 19003; // 网关查询房间列表端口（备用）

  // 房间服务器注册端口
  public static final int ROOM_REGISTER_PORT = 19002;

  // 客户端
  public static final int MAX_RETRY_ATTEMPTS = 5;
  public static final int RETRY_DELAY_MS = 200;
}
