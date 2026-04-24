package snake.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import snake.base.ILogger;
import snake.base.Logger;

/** 数据库仓储抽象基类，提供表初始化、密码哈希等公共能力。 DataSource 由子类通过构造函数注入。 */
public abstract class BaseMySQLRepository {
  protected final DataSource dataSource;
  protected final ILogger logger = Logger.getInstance();

  public BaseMySQLRepository(DataSource dataSource) {
    this.dataSource = dataSource;
    createTableIfNotExists();
  }

  /** 若 users 表不存在则创建。 */
  protected void createTableIfNotExists() {
    String sql =
        "CREATE TABLE IF NOT EXISTS users ("
            + "username VARCHAR(32) PRIMARY KEY,"
            + "salt INT NOT NULL,"
            + "password_hash BINARY(32) NOT NULL,"
            + "online TINYINT(1) DEFAULT 0,"
            + "last_active BIGINT DEFAULT 0,"
            + "high_score INT DEFAULT 0"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
      logger.info("Users table ensured.");
    } catch (SQLException e) {
      logger.error("Failed to create users table: " + e.getMessage());
    }
  }

  /** 使用 SHA-256 对密码加盐哈希。 */
  protected byte[] hashPassword(String password, int salt) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String salted = password + String.format("%08x", salt);
      return md.digest(salted.getBytes());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }
}
