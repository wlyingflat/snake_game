package snake.infrastructure.persistence.leaderboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import snake.common.ILogger;
import snake.common.Logger;

public class RedissonLeaderboardRepository implements ILeaderboardRepository {
  private static final String LEADERBOARD_KEY = "leaderboard";
  private final RedissonClient redisson;
  private final MySQLLeaderboardRepository mysqlRepo;
  private final ILogger logger = Logger.getInstance();

  public RedissonLeaderboardRepository(
      RedissonClient redisson, MySQLLeaderboardRepository mysqlRepo) {
    this.redisson = redisson;
    this.mysqlRepo = mysqlRepo;
  }

  @Override
  public boolean updateHighScore(String username, int newScore) {
    RScoredSortedSet<String> leaderboard =
        redisson.getScoredSortedSet(LEADERBOARD_KEY, StringCodec.INSTANCE);
    boolean redisOk = false;
    try {
      Double current = leaderboard.getScore(username);
      if (current != null && current >= newScore) {
        return false;
      }
      // 添加或更新分数（Redisson 的 add 会自动更新）
      leaderboard.add(newScore, username);
      redisOk = true;
      logger.debug("Redis updated: " + username + " -> " + newScore);
    } catch (Exception e) {
      logger.error("Redis update failed: " + e.getMessage());
    }

    boolean mysqlOk = false;
    try {
      mysqlOk = mysqlRepo.updateHighScore(username, newScore);
      if (mysqlOk) {
        logger.info("MySQL updated: " + username + " -> " + newScore);
      } else {
        logger.warn("MySQL update returned false for " + username);
      }
    } catch (Exception e) {
      logger.error("MySQL update failed: " + e.getMessage());
    }

    // 回滚 Redis 如果 MySQL 失败
    if (redisOk && !mysqlOk) {
      try {
        Double current = leaderboard.getScore(username);
        if (current != null && current == newScore) {
          leaderboard.remove(username);
          logger.warn("Rolled back Redis for " + username + " due to MySQL failure");
        }
      } catch (Exception ex) {
        logger.error("Failed to rollback Redis: " + ex.getMessage());
      }
      return false;
    }
    return redisOk && mysqlOk;
  }

  @Override
  public List<UserRank> getLeaderboard(int limit) {
    RScoredSortedSet<String> leaderboard =
        redisson.getScoredSortedSet(LEADERBOARD_KEY, StringCodec.INSTANCE);
    try {
      // 获取倒序前 limit 个元素（带分数）
      Collection<String> usernames = leaderboard.valueRangeReversed(0, limit - 1);
      if (usernames != null && !usernames.isEmpty()) {
        List<UserRank> ranks = new ArrayList<>();
        int rank = 1;
        for (String username : usernames) {
          Double score = leaderboard.getScore(username);
          if (score != null) {
            ranks.add(new UserRank(rank++, username, score.intValue()));
          }
        }
        logger.debug("Leaderboard fetched from Redis, size=" + ranks.size());
        return ranks;
      }
    } catch (Exception e) {
      logger.error("Redis getLeaderboard failed: " + e.getMessage());
    }

    // 降级到 MySQL
    logger.warn("Fallback to MySQL for leaderboard");
    List<UserRank> mysqlRanks = mysqlRepo.getLeaderboard(limit);
    if (!mysqlRanks.isEmpty()) {
      rebuildCacheAsync(mysqlRanks);
    }
    return mysqlRanks;
  }

  /** 从 MySQL 全量加载到 Redis */
  public void loadFromMySQL() {
    List<UserRank> allRanks = mysqlRepo.getLeaderboard(Integer.MAX_VALUE);
    RScoredSortedSet<String> leaderboard =
        redisson.getScoredSortedSet(LEADERBOARD_KEY, StringCodec.INSTANCE);
    for (UserRank rank : allRanks) {
      leaderboard.add(rank.score, rank.username);
    }
    logger.info("Loaded " + allRanks.size() + " records from MySQL to Redis");
  }

  /** 异步重建缓存（降级后） */
  private void rebuildCacheAsync(List<UserRank> ranks) {
    new Thread(
            () -> {
              RScoredSortedSet<String> leaderboard =
                  redisson.getScoredSortedSet(LEADERBOARD_KEY, StringCodec.INSTANCE);
              for (UserRank rank : ranks) {
                leaderboard.add(rank.score, rank.username);
              }
              logger.info("Rebuilt Redis cache with " + ranks.size() + " records");
            },
            "cache-rebuilder")
        .start();
  }

  public void close() {
    // RedissonClient 由 RedissonManager 统一关闭，这里不做关闭
  }
}
