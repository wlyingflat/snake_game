package snake.worker;

import java.util.UUID;
import org.redisson.api.RedissonClient;
import snake.base.*;
import snake.distributed.DistributedCoordinator;
import snake.persistence.DatabaseManager;
import snake.persistence.PropertiesConfigProvider;
import snake.persistence.leaderboard.MySQLLeaderboardRepository;
import snake.persistence.leaderboard.RedissonLeaderboardRepository;
import snake.persistence.redis.RedissonManager;

/** Game Worker 启动入口 */
public class WorkerMain {
  public static void main(String[] args) {
    boolean distributedMode = Boolean.parseBoolean(System.getProperty("distributed.mode", "false"));
    String workerId =
        System.getProperty("node.id", "worker-" + UUID.randomUUID().toString().substring(0, 8));

    ILogger logger = Logger.getInstance();
    logger.info("Starting Game Worker " + workerId);

    IConfigProvider config = new PropertiesConfigProvider("config.properties");
    DatabaseManager dbManager = DatabaseManager.getInstance(config);

    final RedissonClient redisson;
    final DistributedCoordinator coordinator;
    if (distributedMode) {
      redisson = RedissonManager.getInstance(config);
      coordinator = new DistributedCoordinator(redisson, workerId);
    } else {
      redisson = null;
      coordinator = null;
    }

    // 构建排行榜仓库（同时包含 Redis 和 MySQL 双写能力）
    ILeaderboardRepository leaderboardRepo = null;
    if (distributedMode && coordinator != null) {
      MySQLLeaderboardRepository mysqlRepo =
          new MySQLLeaderboardRepository(dbManager.getDataSource());
      RedissonLeaderboardRepository redissonLeaderboardRepo =
          new RedissonLeaderboardRepository(redisson, mysqlRepo);
      redissonLeaderboardRepo.loadFromMySQL(); // 初始化 Redis 缓存
      leaderboardRepo = redissonLeaderboardRepo;
    }

    GameWorker worker = new GameWorker(workerId, coordinator, leaderboardRepo);
    worker.start();
    logger.info("Worker " + workerId + " started");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down worker " + workerId + "...");
                  worker.stop();
                  if (redisson != null) redisson.shutdown();
                  dbManager.shutdown();
                }));

    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
