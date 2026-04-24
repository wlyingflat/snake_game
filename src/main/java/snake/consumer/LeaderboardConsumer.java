package snake.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import snake.base.*;
import snake.persistence.DatabaseManager;
import snake.persistence.PropertiesConfigProvider;
import snake.persistence.leaderboard.MySQLLeaderboardRepository;
import snake.persistence.redis.RedissonManager;

public class LeaderboardConsumer implements Runnable {
  private final ILeaderboardRepository leaderboardRepo; // MySQL 实现
  private final RedissonClient redisson;
  private final KafkaConsumer<String, String> consumer;
  private final ILogger logger = Logger.getInstance();
  private volatile boolean running = true;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  public LeaderboardConsumer(ILeaderboardRepository leaderboardRepo, RedissonClient redisson) {
    this.leaderboardRepo = leaderboardRepo;
    this.redisson = redisson;
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

  /** 全量加载 MySQL 排行榜到 Redis（覆盖） */
  private void loadAllScoresToRedis() {
    if (redisson == null || redisson.isShutdown()) return;
    try {
      // 从 MySQL 获取所有高分（getLeaderboard(Integer.MAX_VALUE)）
      List<ILeaderboardRepository.UserRank> allRanks =
          leaderboardRepo.getLeaderboard(Integer.MAX_VALUE);
      RScoredSortedSet<String> leaderboard =
          redisson.getScoredSortedSet("leaderboard", StringCodec.INSTANCE);
      // 清空原有数据
      leaderboard.clear();
      for (ILeaderboardRepository.UserRank rank : allRanks) {
        leaderboard.add(rank.score, rank.username);
      }
      logger.info("Loaded " + allRanks.size() + " records from MySQL to Redis");
    } catch (Exception e) {
      logger.error("Failed to load leaderboard to Redis: " + e.getMessage());
    }
  }

  /** 增量更新单条记录（由 Kafka 事件触发） */
  private void updateScoreInRedis(String username, int newScore) {
    if (redisson == null || redisson.isShutdown()) return;
    try {
      RScoredSortedSet<String> leaderboard =
          redisson.getScoredSortedSet("leaderboard", StringCodec.INSTANCE);
      Double current = leaderboard.getScore(username);
      if (current == null || current < newScore) {
        leaderboard.add(newScore, username);
        logger.debug("Redis updated for " + username + " with score " + newScore);
      }
    } catch (Exception e) {
      logger.error("Failed to update Redis for " + username + ": " + e.getMessage());
    }
  }

  @Override
  public void run() {
    // 启动时先全量加载一次
    loadAllScoresToRedis();

    // 定时同步（每小时一次），确保 Redis 不丢失
    scheduler.scheduleAtFixedRate(this::loadAllScoresToRedis, 1, 1, TimeUnit.HOURS);

    logger.info("LeaderboardConsumer started, listening to game.player.died");
    while (running) {
      try {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
        for (ConsumerRecord<String, String> record : records) {
          try {
            JsonNode event = JsonUtils.MAPPER.readTree(record.value());
            if ("PlayerDied".equals(event.get("eventType").asText())) {
              JsonNode payload = event.get("payload");
              String username = payload.get("username").asText();
              int finalScore = payload.get("score").asInt();

              // 1. 更新 MySQL（如果分数更高）
              boolean mysqlUpdated = leaderboardRepo.updateHighScore(username, finalScore);
              // 2. 更新 Redis（如果分数更高）
              updateScoreInRedis(username, finalScore);

              if (mysqlUpdated) {
                logger.info("Leaderboard updated for " + username + " with score " + finalScore);
              }
            }
          } catch (Exception e) {
            logger.error("Failed to process event: " + e.getMessage());
          }
        }
      } catch (Exception e) {
        logger.error("Consumer loop error: " + e.getMessage());
      }
    }
    scheduler.shutdown();
    consumer.close();
    logger.info("LeaderboardConsumer stopped");
  }

  public void stop() {
    running = false;
    consumer.wakeup();
  }

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
