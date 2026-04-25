package snake.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.*;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.leaderboard.ILeaderboardRepository;

public class LeaderboardEventHandler {
  private final ILeaderboardRepository leaderboardRepo;
  private final RedissonClient redisson;
  private final ILogger logger = Logger.getInstance();

  // 异步写 MySQL 的线程池（单线程，保证顺序）
  private final ExecutorService mysqlExecutor =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "leaderboard-mysql-writer"));

  public LeaderboardEventHandler(ILeaderboardRepository leaderboardRepo, RedissonClient redisson) {
    this.leaderboardRepo = leaderboardRepo;
    this.redisson = redisson;
  }

  public void handle(JsonNode event) {
    try {
      if (!"PlayerDied".equals(event.get("eventType").asText())) return;
      JsonNode payload = event.get("payload");
      String username = payload.get("username").asText();
      int finalScore = payload.get("score").asInt();

      // 1. 立即更新 Redis（同步）
      updateRedis(username, finalScore);

      // 2. 异步写 MySQL，不阻塞 Kafka 消费
      mysqlExecutor.submit(
          () -> {
            try {
              boolean updated = leaderboardRepo.updateHighScore(username, finalScore);
              if (updated) {
                logger.info(
                    "MySQL leaderboard updated for " + username + " with score " + finalScore);
              }
            } catch (Exception e) {
              logger.error("Failed to update MySQL for " + username + ": " + e.getMessage());
            }
          });
    } catch (Exception e) {
      logger.error("Failed to process event: " + e.getMessage());
    }
  }

  private void updateRedis(String username, int newScore) {
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
      // Redis 失败不影响后续，排行榜将依赖 MySQL 定时同步
    }
  }

  /** 关闭线程池 */
  public void shutdown() {
    mysqlExecutor.shutdown();
  }
}
