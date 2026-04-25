package snake.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import snake.common.IConfigProvider;
import snake.common.ILogger;
import snake.common.Logger;

/** 数据库连接池管理器（单例），提供全局唯一的 DataSource。 */
public class DatabaseManager {
  private static volatile DatabaseManager instance;
  private final HikariDataSource dataSource;
  private final ILogger logger = Logger.getInstance();

  private DatabaseManager(IConfigProvider config) {
    this.dataSource = createDataSource(config);
    logger.info("Database connection pool initialized.");
  }

  public static DatabaseManager getInstance(IConfigProvider config) {
    if (instance == null) {
      synchronized (DatabaseManager.class) {
        if (instance == null) {
          instance = new DatabaseManager(config);
        }
      }
    }
    return instance;
  }

  private HikariDataSource createDataSource(IConfigProvider config) {
    HikariConfig hikariConfig = new HikariConfig();
    String host = config.getString("db.host", "localhost");
    int port = config.getInt("db.port", 3306);
    String dbName = config.getString("db.name", "snake_game");
    String user = config.getString("db.user", "root");
    String password = config.getString("db.password", "");
    int poolSize = config.getInt("db.pool.size", 10);

    String jdbcUrl =
        String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            host, port, dbName);
    hikariConfig.setJdbcUrl(jdbcUrl);
    hikariConfig.setUsername(user);
    hikariConfig.setPassword(password);
    hikariConfig.setMaximumPoolSize(poolSize);
    hikariConfig.setMinimumIdle(2);
    hikariConfig.setConnectionTimeout(30000);
    hikariConfig.setIdleTimeout(600000);
    hikariConfig.setMaxLifetime(1800000);
    return new HikariDataSource(hikariConfig);
  }

  public DataSource getDataSource() {
    return dataSource;
  }

  public void shutdown() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
      logger.info("Database connection pool closed.");
    }
  }
}
