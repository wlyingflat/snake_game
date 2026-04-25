package snake.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import snake.base.ILogger;
import snake.base.Logger;

public abstract class BaseMySQLRepository {
  protected final DataSource dataSource;
  protected final ILogger logger = Logger.getInstance();

  public BaseMySQLRepository(DataSource dataSource) {
    this.dataSource = dataSource;
    createTableIfNotExists();
  }

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
}
