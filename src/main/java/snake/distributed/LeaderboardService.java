package snake.distributed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 负责排行榜（从 Redis Sorted Set）查询。 */
public class LeaderboardService {
  private final RedissonClient redisson;

  public LeaderboardService(RedissonClient redisson) {
    this.redisson = redisson;
  }

  public List<UserRank> getLeaderboard(int limit) {
    RScoredSortedSet<String> leaderboard =
        redisson.getScoredSortedSet(RedisKeys.LEADERBOARD_KEY, StringCodec.INSTANCE);
    List<UserRank> ranks = new ArrayList<>();
    Collection<String> usernames = leaderboard.valueRangeReversed(0, limit - 1);
    if (usernames != null) {
      int rank = 1;
      for (String username : usernames) {
        Double score = leaderboard.getScore(username);
        if (score != null) {
          ranks.add(new UserRank(rank++, username, score.intValue()));
        }
      }
    }
    return ranks;
  }

  public static class UserRank {
    public final int rank;
    public final String username;
    public final int score;

    public UserRank(int rank, String username, int score) {
      this.rank = rank;
      this.username = username;
      this.score = score;
    }
  }
}
