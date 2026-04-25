package snake.infrastructure.persistence.leaderboard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import snake.infrastructure.persistence.BaseMySQLRepository;

/** 排行榜仓储实现，负责高分更新与查询。 */
public class MySQLLeaderboardRepository extends BaseMySQLRepository
    implements ILeaderboardRepository {

  public MySQLLeaderboardRepository(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  public boolean updateHighScore(String username, int newScore) {
    String selectSql = "SELECT high_score FROM users WHERE username = ?";
    String updateSql = "UPDATE users SET high_score = ? WHERE username = ? AND high_score < ?";

    try (Connection conn = dataSource.getConnection()) {
      // 查询当前最高分
      int currentHigh = 0;
      try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
        ps.setString(1, username);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            currentHigh = rs.getInt("high_score");
          } else {
            return false; // 用户不存在
          }
        }
      }

      if (newScore <= currentHigh) {
        return false; // 未破纪录
      }

      // 更新分数
      try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
        ps.setInt(1, newScore);
        ps.setString(2, username);
        ps.setInt(3, newScore);
        int rows = ps.executeUpdate();
        if (rows > 0) {
          logger.info("High score updated for " + username + ": " + newScore);
          return true;
        }
      }
    } catch (SQLException e) {
      logger.error("Failed to update high score for " + username + ": " + e.getMessage());
    }
    return false;
  }

  @Override
  public List<UserRank> getLeaderboard(int limit) {
    List<UserRank> list = new ArrayList<>();
    String sql =
        "SELECT username, high_score FROM users WHERE high_score > 0 ORDER BY high_score DESC LIMIT"
            + " ?";

    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        int rank = 1;
        while (rs.next()) {
          list.add(new UserRank(rank++, rs.getString("username"), rs.getInt("high_score")));
        }
      }
    } catch (SQLException e) {
      logger.error("Failed to get leaderboard: " + e.getMessage());
    }
    return list;
  }
}
