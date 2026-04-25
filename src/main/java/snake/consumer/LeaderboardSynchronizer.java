package snake.consumer;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.leaderboard.ILeaderboardRepository;

/** 定时全量同步 MySQL 排行榜到 Redis。 */
public class LeaderboardSynchronizer {
  private final ILeaderboardRepository leaderboardRepo;
  private final RedissonClient redisson;
  private final ILogger logger = Logger.getInstance();
  private final ScheduledExecutorService scheduler;

  public LeaderboardSynchronizer(ILeaderboardRepository leaderboardRepo, RedissonClient redisson) {
    this.leaderboardRepo = leaderboardRepo;
    this.redisson = redisson;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "leaderboard-sync");
              t.setDaemon(true);
              return t;
            });
  }

  /** 执行一次全量加载。 */
  public void syncOnce() {
    if (redisson == null || redisson.isShutdown()) return;
    try {
      List<ILeaderboardRepository.UserRank> allRanks =
          leaderboardRepo.getLeaderboard(Integer.MAX_VALUE);
      RScoredSortedSet<String> leaderboard =
          redisson.getScoredSortedSet("leaderboard", StringCodec.INSTANCE);
      leaderboard.clear();
      for (ILeaderboardRepository.UserRank rank : allRanks) {
        leaderboard.add(rank.score, rank.username);
      }
      logger.info("Loaded " + allRanks.size() + " records from MySQL to Redis");
    } catch (Exception e) {
      logger.error("Failed to load leaderboard to Redis: " + e.getMessage());
    }
  }

  /** 启动定时同步（每小时一次）。 */
  public void startScheduledSync() {
    scheduler.scheduleAtFixedRate(this::syncOnce, 1, 1, TimeUnit.HOURS);
  }

  public void stop() {
    scheduler.shutdown();
  }
}
