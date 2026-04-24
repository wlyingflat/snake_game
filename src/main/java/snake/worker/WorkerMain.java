package snake.worker;

import java.util.UUID;
import org.redisson.api.RedissonClient;
import snake.base.*;
import snake.distributed.DistributedCoordinator;
import snake.mq.MessageBus; // 新增
import snake.persistence.DatabaseManager;
import snake.persistence.PropertiesConfigProvider;
import snake.persistence.leaderboard.MySQLLeaderboardRepository;
import snake.persistence.leaderboard.RedissonLeaderboardRepository;
import snake.persistence.redis.RedissonManager;

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

    // 排行榜仓库
    ILeaderboardRepository leaderboardRepo = null;
    if (distributedMode && coordinator != null) {
      MySQLLeaderboardRepository mysqlRepo =
          new MySQLLeaderboardRepository(dbManager.getDataSource());
      RedissonLeaderboardRepository redissonRepo =
          new RedissonLeaderboardRepository(redisson, mysqlRepo);
      redissonRepo.loadFromMySQL();
      leaderboardRepo = redissonRepo;
    }

    // 创建 MessageBus 连接 RabbitMQ
    MessageBus messageBus = null;
    try {
      messageBus = new MessageBus();
    } catch (Exception e) {
      logger.error("Failed to connect to RabbitMQ: " + e.getMessage());
      System.exit(1);
    }

    GameWorker worker = new GameWorker(workerId, coordinator, leaderboardRepo, messageBus);
    try {
      worker.start();
    } catch (Exception e) {
      logger.error("Failed to start worker: " + e.getMessage());
      System.exit(1);
    }
    logger.info("Worker " + workerId + " started");

    final MessageBus finalMessageBus = messageBus;
    final RedissonClient finalRedisson = redisson;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down worker " + workerId + "...");
                  worker.stop();
                  if (finalMessageBus != null) finalMessageBus.close();
                  if (finalRedisson != null) finalRedisson.shutdown();
                  dbManager.shutdown();
                }));

    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
