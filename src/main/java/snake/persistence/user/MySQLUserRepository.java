package snake.persistence.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import snake.base.User;
import snake.persistence.BaseMySQLRepository;

/** 用户仓储实现，只负责 CRUD，不包含任何业务逻辑。 保留 BaseMySQLRepository 的建表能力和 DataSource 引用。 */
public class MySQLUserRepository extends BaseMySQLRepository implements IUserRepository {

  public MySQLUserRepository(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  public User findByName(String username) {
    String sql = "SELECT salt, password_hash, online, last_active FROM users WHERE username = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          User user = new User();
          user.name = username;
          user.salt = rs.getInt("salt");
          user.passwordHash = rs.getBytes("password_hash");
          user.online = rs.getBoolean("online");
          user.lastActive = rs.getLong("last_active");
          return user;
        }
      }
    } catch (SQLException e) {
      logger.error("Find user error: " + e.getMessage());
    }
    return null;
  }

  @Override
  public void save(User user) {
    String sql =
        "UPDATE users SET salt = ?, password_hash = ?, online = ?, last_active = ? WHERE username ="
            + " ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, user.salt);
      ps.setBytes(2, user.passwordHash);
      ps.setBoolean(3, user.online);
      ps.setLong(4, user.lastActive);
      ps.setString(5, user.name);
      ps.executeUpdate();
    } catch (SQLException e) {
      logger.error("Save user error: " + e.getMessage());
    }
  }

  @Override
  public void delete(String username) {
    String sql = "DELETE FROM users WHERE username = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, username);
      ps.executeUpdate();
      logger.info("User deleted: " + username);
    } catch (SQLException e) {
      logger.error("Delete user error: " + e.getMessage());
    }
  }

  @Override
  public List<User> findAll() {
    List<User> list = new ArrayList<>();
    String sql = "SELECT username, salt, password_hash, online, last_active FROM users";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        User user = new User();
        user.name = rs.getString("username");
        user.salt = rs.getInt("salt");
        user.passwordHash = rs.getBytes("password_hash");
        user.online = rs.getBoolean("online");
        user.lastActive = rs.getLong("last_active");
        list.add(user);
      }
    } catch (SQLException e) {
      logger.error("Find all users error: " + e.getMessage());
    }
    return list;
  }

  /**
   * 创建一个新用户（用于注册）。
   *
   * @param user 用户名、盐、哈希必须已设置；online 和 last_active 由本方法强制置为 false 和当前时间。
   * @return true 如果插入成功，false 表示用户名已存在。
   */
  @Override
  public boolean createUser(User user) {
    String sql =
        "INSERT INTO users (username, salt, password_hash, online, last_active) VALUES (?, ?, ?, 0,"
            + " ?)";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, user.name);
      ps.setInt(2, user.salt);
      ps.setBytes(3, user.passwordHash);
      ps.setLong(4, System.currentTimeMillis() / 1000);
      ps.executeUpdate();
      logger.info("User created: " + user.name);
      return true;
    } catch (SQLIntegrityConstraintViolationException e) {
      logger.warn("Username already exists: " + user.name);
      return false;
    } catch (SQLException e) {
      logger.error("Create user error: " + e.getMessage());
      return false;
    }
  }

  /** 更新登录/登出状态 */
  @Override
  public void updateOnlineStatus(String username, boolean online, long lastActive) {
    String sql = "UPDATE users SET online = ?, last_active = ? WHERE username = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setBoolean(1, online);
      ps.setLong(2, lastActive);
      ps.setString(3, username);
      int rows = ps.executeUpdate();
      if (rows > 0) {
        logger.debug("Online status updated for " + username + ": " + online);
      }
    } catch (SQLException e) {
      logger.error("Update online status error: " + e.getMessage());
    }
  }
}
