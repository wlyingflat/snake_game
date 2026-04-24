package snake.auth;

import javax.sql.DataSource;
import snake.base.Config;
import snake.base.IConfigProvider;
import snake.base.ILogger;
import snake.base.Logger;
import snake.persistence.DatabaseManager;
import snake.persistence.PropertiesConfigProvider;
import snake.persistence.user.MySQLUserRepository;

/**
 * 认证服务主入口，启动内嵌 HTTP 服务器，提供用户注册、登录、登出 REST API。 使用方式: java snake.auth.MainServer [port] 默认端口: 9000
 */
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
      // 加载配置
      IConfigProvider config = new PropertiesConfigProvider("config.properties");
      DatabaseManager dbManager = DatabaseManager.getInstance(config);
      DataSource dataSource = dbManager.getDataSource();

      // 认证服务实现（基于 MySQL）
      IAuthenticationService authService = new MySQLUserRepository(dataSource);

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

      // 阻塞主线程（Jetty 已使用非守护线程）
      Thread.currentThread().join();

    } catch (Exception e) {
      logger.error("Failed to start auth service: " + e.getMessage());
      e.printStackTrace(); // 可选，输出完整堆栈到标准错误
      System.exit(1);
    }
  }
}
