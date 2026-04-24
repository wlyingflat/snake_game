package snake.persistence.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import snake.auth.IAuthenticationService;
import snake.base.User;
import snake.persistence.BaseMySQLRepository;

/** 用户仓储实现，同时提供认证服务。 */
public class MySQLUserRepository extends BaseMySQLRepository
    implements IUserRepository, IAuthenticationService {

  public MySQLUserRepository(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  public boolean register(String username, String password) {
    User user = new User();
    user.name = username;
    user.salt = (int) (Math.random() * 0xFFFFFFFFL);
    user.passwordHash = hashPassword(password, user.salt);
    user.online = false;
    user.lastActive = System.currentTimeMillis() / 1000;

    String sql =
        "INSERT INTO users (username, salt, password_hash, online, last_active) VALUES (?, ?, ?, ?,"
            + " ?)";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, username);
      ps.setInt(2, user.salt);
      ps.setBytes(3, user.passwordHash);
      ps.setBoolean(4, user.online);
      ps.setLong(5, user.lastActive);
      ps.executeUpdate();
      logger.info("User registered: " + username);
      return true;
    } catch (SQLIntegrityConstraintViolationException e) {
      logger.warn("Username already exists: " + username);
      return false;
    } catch (SQLException e) {
      logger.error("Registration error: " + e.getMessage());
      return false;
    }
  }

  @Override
  public boolean login(String username, String password) {
    User user = findByName(username);
    if (user == null) {
      return false;
    }

    byte[] hash = hashPassword(password, user.salt);
    if (!Arrays.equals(hash, user.passwordHash)) {
      return false;
    }

    // 禁止重复登录
    if (user.online) {
      return false;
    }

    String sql = "UPDATE users SET online = 1, last_active = ? WHERE username = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      long now = System.currentTimeMillis() / 1000;
      ps.setLong(1, now);
      ps.setString(2, username);
      ps.executeUpdate();
      user.online = true;
      user.lastActive = now;
      logger.info("User logged in: " + username);
      return true;
    } catch (SQLException e) {
      logger.error("Login update error: " + e.getMessage());
      return false;
    }
  }

  @Override
  public void logout(String username) {
    String sql = "UPDATE users SET online = 0, last_active = ? WHERE username = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setLong(1, System.currentTimeMillis() / 1000);
      ps.setString(2, username);
      int updated = ps.executeUpdate();
      if (updated > 0) {
        logger.info("User logged out: " + username);
      }
    } catch (SQLException e) {
      logger.error("Logout error: " + e.getMessage());
    }
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
}
