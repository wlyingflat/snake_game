package snake.util;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Logger {
  public static void info(String msg) {
    System.out.println("[" + timestamp() + "] INFO: " + msg);
  }

  public static void warn(String msg) {
    System.out.println("[" + timestamp() + "] WARN: " + msg);
  }

  public static void error(String msg) {
    System.err.println("[" + timestamp() + "] ERROR: " + msg);
  }

  public static void debug(String msg) {
    // 可开关
    System.out.println("[" + timestamp() + "] DEBUG: " + msg);
  }

  private static String timestamp() {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
  }
}
