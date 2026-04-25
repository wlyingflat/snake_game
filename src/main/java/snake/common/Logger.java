package snake.common;

import org.apache.logging.log4j.LogManager;

public class Logger implements ILogger {
  private static ILogger instance = new Logger();
  private final org.apache.logging.log4j.Logger logger = LogManager.getLogger(Logger.class);

  public static ILogger getInstance() {
    return instance;
  }

  public static void setInstance(ILogger logger) {
    instance = logger;
  }

  @Override
  public void info(String msg) {
    logger.info(msg);
  }

  @Override
  public void warn(String msg) {
    logger.warn(msg);
  }

  @Override
  public void error(String msg) {
    logger.error(msg);
  }

  @Override
  public void debug(String msg) {
    logger.debug(msg);
  }
}
