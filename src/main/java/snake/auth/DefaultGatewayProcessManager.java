package snake.auth;

import java.io.File;
import java.io.IOException;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;

public class DefaultGatewayProcessManager implements IGatewayProcessManager {
  private Process gatewayProcess;
  private final ILogger logger = Logger.getInstance();

  @Override
  public void startGateway(int port) {
    try {
      String projectDir = System.getProperty("user.dir");
      ProcessBuilder pb =
          new ProcessBuilder(
              "mvn",
              "exec:java",
              "-Dexec.mainClass=snake.gateway.Gateway",
              "-Dexec.args=" + Config.GATEWAY_DEFAULT_PORT);
      pb.directory(new File(projectDir));
      pb.inheritIO();
      gatewayProcess = pb.start();
      logger.info("Gateway process started on port " + port);
      Thread.sleep(500);
    } catch (IOException | InterruptedException e) {
      logger.error("Failed to start gateway: " + e.getMessage());
    }
  }

  @Override
  public void stopGateway() {
    if (gatewayProcess != null) {
      gatewayProcess.destroyForcibly();
      logger.info("Gateway process stopped.");
    }
  }
}
