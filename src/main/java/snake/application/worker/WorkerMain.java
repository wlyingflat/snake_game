package snake.application.worker;

import java.util.UUID;
import org.redisson.api.RedissonClient;
import snake.common.IConfigProvider;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.event.KafkaEventProducer;
import snake.infrastructure.messaging.MessageBus;
import snake.infrastructure.persistence.DatabaseManager;
import snake.infrastructure.persistence.PropertiesConfigProvider;
import snake.infrastructure.persistence.RedissonManager;

/** Worker 服务主入口 - 注册到 Redis（分布式模式） - 连接 RabbitMQ 接收指令 - 初始化 Kafka 事件生产者 - 启动 GameWorker 实例 */
public class WorkerMain {

  public static void main(String[] args) {
    boolean distributedMode = Boolean.parseBoolean(System.getProperty("distributed.mode", "false"));
    String workerId =
        System.getProperty("node.id", "worker-" + UUID.randomUUID().toString().substring(0, 8));

    ILogger logger = Logger.getInstance();
    logger.info("Starting Game Worker " + workerId);

    // 加载配置
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

    // 创建 RabbitMQ 消息总线
    MessageBus messageBus = null;
    try {
      messageBus = new MessageBus();
      logger.info("MessageBus connected to RabbitMQ");
    } catch (Exception e) {
      logger.error("Failed to connect to RabbitMQ: " + e.getMessage());
      System.exit(1);
    }

    // 创建 Kafka 事件生产者（用于发送游戏事件）
    KafkaEventProducer eventProducer = new KafkaEventProducer();

    // 构建 Worker 实例（不再依赖 ILeaderboardRepository）
    GameWorker worker = new GameWorker(workerId, coordinator, messageBus, eventProducer);

    try {
      worker.start();
      logger.info("Worker " + workerId + " started successfully");
    } catch (Exception e) {
      logger.error("Failed to start worker: " + e.getMessage());
      System.exit(1);
    }

    // 注册关闭钩子
    final MessageBus finalMessageBus = messageBus;
    final RedissonClient finalRedisson = redisson;
    final KafkaEventProducer finalEventProducer = eventProducer;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down worker " + workerId + "...");
                  worker.stop();
                  if (finalMessageBus != null) {
                    finalMessageBus.close();
                  }
                  if (finalEventProducer != null) {
                    finalEventProducer.close();
                  }
                  if (finalRedisson != null && !finalRedisson.isShutdown()) {
                    finalRedisson.shutdown();
                  }
                  dbManager.shutdown();
                  logger.info("Worker " + workerId + " shut down complete");
                }));

    // 保持主线程存活
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn("Worker main thread interrupted");
    }
  }
}
