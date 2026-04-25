package snake.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import org.apache.kafka.clients.consumer.*;
import org.redisson.api.RedissonClient;
import snake.common.Config;
import snake.common.IConfigProvider;
import snake.common.ILogger;
import snake.common.JsonUtils;
import snake.common.Logger;
import snake.infrastructure.persistence.DatabaseManager;
import snake.infrastructure.persistence.PropertiesConfigProvider;
import snake.infrastructure.persistence.RedissonManager;
import snake.infrastructure.persistence.leaderboard.ILeaderboardRepository;
import snake.infrastructure.persistence.leaderboard.MySQLLeaderboardRepository;

public class LeaderboardConsumer implements Runnable {
  private final LeaderboardEventHandler eventHandler;
  private final LeaderboardSynchronizer synchronizer;
  private final KafkaConsumer<String, String> consumer;
  private final ILogger logger = Logger.getInstance();
  private volatile boolean running = true;

  public LeaderboardConsumer(ILeaderboardRepository leaderboardRepo, RedissonClient redisson) {
    this.eventHandler = new LeaderboardEventHandler(leaderboardRepo, redisson);
    this.synchronizer = new LeaderboardSynchronizer(leaderboardRepo, redisson);

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Config.KAFKA_BOOTSTRAP_SERVERS);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "leaderboard-updater");
    props.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringDeserializer");
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringDeserializer");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    this.consumer = new KafkaConsumer<>(props);
    consumer.subscribe(Collections.singletonList("game.player.died"));
  }

  @Override
  public void run() {
    // 启动时全量同步一次
    synchronizer.syncOnce();
    // 每小时定时同步
    synchronizer.startScheduledSync();

    logger.info("LeaderboardConsumer started, listening to game.player.died");
    while (running) {
      try {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
        for (ConsumerRecord<String, String> record : records) {
          JsonNode event = JsonUtils.MAPPER.readTree(record.value());
          eventHandler.handle(event);
        }
      } catch (Exception e) {
        logger.error("Consumer loop error: " + e.getMessage());
      }
    }
    synchronizer.stop();
    consumer.close();
    logger.info("LeaderboardConsumer stopped");
  }

  public void stop() {
    running = false;
    consumer.wakeup();
  }

  // main 方法保持不变，但内部改用新的构造函数
  public static void main(String[] args) {
    IConfigProvider configProvider = new PropertiesConfigProvider("config.properties");
    DatabaseManager dbManager = DatabaseManager.getInstance(configProvider);
    MySQLLeaderboardRepository mysqlRepo =
        new MySQLLeaderboardRepository(dbManager.getDataSource());

    RedissonClient redisson = null;
    try {
      redisson = RedissonManager.getInstance(configProvider);
      Logger.getInstance().info("Connected to Redis for leaderboard consumer");
    } catch (Exception e) {
      Logger.getInstance().warn("Redis not available, leaderboard will only be stored in MySQL");
    }

    final RedissonClient finalRedisson = redisson;
    final DatabaseManager finalDbManager = dbManager;

    LeaderboardConsumer consumer = new LeaderboardConsumer(mysqlRepo, finalRedisson);
    Thread t = new Thread(consumer, "leaderboard-consumer");
    t.setDaemon(false);
    t.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  consumer.stop();
                  if (finalRedisson != null && !finalRedisson.isShutdown()) {
                    finalRedisson.shutdown();
                  }
                  finalDbManager.shutdown();
                }));
  }
}
