package snake.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.leaderboard.ILeaderboardRepository;

/** 处理游戏事件，更新排行榜存储。 */
public class LeaderboardEventHandler {
  private final ILeaderboardRepository leaderboardRepo;
  private final RedissonClient redisson;
  private final ILogger logger = Logger.getInstance();

  public LeaderboardEventHandler(ILeaderboardRepository leaderboardRepo, RedissonClient redisson) {
    this.leaderboardRepo = leaderboardRepo;
    this.redisson = redisson;
  }

  /** 处理一条 PlayerDied 事件 JSON。 */
  public void handle(JsonNode event) {
    try {
      if (!"PlayerDied".equals(event.get("eventType").asText())) return;
      JsonNode payload = event.get("payload");
      String username = payload.get("username").asText();
      int finalScore = payload.get("score").asInt();

      // 更新 MySQL
      boolean mysqlUpdated = leaderboardRepo.updateHighScore(username, finalScore);
      // 更新 Redis
      updateRedis(username, finalScore);

      if (mysqlUpdated) {
        logger.info("Leaderboard updated for " + username + " with score " + finalScore);
      }
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
    }
  }
}
