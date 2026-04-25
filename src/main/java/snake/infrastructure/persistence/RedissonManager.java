package snake.infrastructure.persistence;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import snake.common.IConfigProvider;

public class RedissonManager {
  private static volatile RedissonClient redisson;

  public static RedissonClient getInstance(IConfigProvider config) {
    if (redisson == null) {
      synchronized (RedissonManager.class) {
        if (redisson == null) {
          String redisHost = config.getString("redis.host", "localhost");
          int redisPort = config.getInt("redis.port", 6379);
          Config redissonConfig = new Config();
          redissonConfig
              .useSingleServer()
              .setAddress("redis://" + redisHost + ":" + redisPort)
              .setConnectionPoolSize(20)
              .setConnectionMinimumIdleSize(10);
          redisson = Redisson.create(redissonConfig);
        }
      }
    }
    return redisson;
  }

  public static void shutdown() {
    if (redisson != null && !redisson.isShutdown()) {
      redisson.shutdown();
    }
  }
}
