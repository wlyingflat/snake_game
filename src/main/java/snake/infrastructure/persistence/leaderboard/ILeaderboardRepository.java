package snake.infrastructure.persistence.leaderboard;

import java.util.List;

public interface ILeaderboardRepository {
  /**
   * 更新用户最高分（仅当新分数更高时）
   *
   * @return true 如果分数被更新
   */
  boolean updateHighScore(String username, int newScore);

  /** 获取排行榜前 N 名 */
  List<UserRank> getLeaderboard(int limit);

  // 内部静态类（也可放在单独文件）
  class UserRank {
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
