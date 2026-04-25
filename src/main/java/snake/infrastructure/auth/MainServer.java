package snake.infrastructure.auth;

import javax.sql.DataSource;
import snake.common.Config;
import snake.common.IConfigProvider;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.persistence.DatabaseManager;
import snake.infrastructure.persistence.PropertiesConfigProvider;
import snake.infrastructure.persistence.user.MySQLUserRepository;

public class MainServer {
  private static final ILogger logger = Logger.getInstance();

  public static void main(String[] args) {
    int port = Config.AUTH_SERVICE_PORT;
    if (args.length > 0) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid port number, using default " + port);
      }
    }

    try {
      IConfigProvider config = new PropertiesConfigProvider("config.properties");
      DatabaseManager dbManager = DatabaseManager.getInstance(config);
      DataSource dataSource = dbManager.getDataSource();

      // 创建用户仓储（纯 CRUD）
      MySQLUserRepository userRepo = new MySQLUserRepository(dataSource);

      // 根据分布式模式决定是否创建 Coordinator
      boolean distributedMode =
          Boolean.parseBoolean(System.getProperty("distributed.mode", "false"));
      DistributedCoordinator coordinator = null;
      if (distributedMode) {
        // 需要 Redisson 连接，这里简化处理，实际应创建 RedissonClient 等
        // 此处仅为示例，假设已经具备相关基础设施
        // coordinator = new DistributedCoordinator(redisson, "auth-server");
        logger.warn("Distributed mode requested but Redisson not configured for auth service");
      }

      // 创建认证服务
      IAuthenticationService authService = new AuthenticationService(userRepo, coordinator);

      // 启动 HTTP 服务器
      AuthHttpServer httpServer = new AuthHttpServer(port, authService);
      httpServer.start();

      logger.info("Auth service started successfully on port " + port);

      // 优雅关闭钩子
      final int finalPort = port;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    logger.info("Shutting down auth service...");
                    try {
                      httpServer.stop();
                    } catch (Exception e) {
                      logger.error("Error stopping HTTP server: " + e.getMessage());
                    }
                    dbManager.shutdown();
                    logger.info("Auth service stopped.");
                  },
                  "ShutdownHook"));

      Thread.currentThread().join();

    } catch (Exception e) {
      logger.error("Failed to start auth service: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}
