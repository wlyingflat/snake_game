package snake.server;

import java.io.*;
import java.net.*;
import snake.util.Logger;

public class GatewayNotifier {
  private String host;
  private int port;

  public GatewayNotifier(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public void notifyRefresh() {
    Logger.debug("[GatewayNotifier] notifyRefresh() called");
    int maxRetries = 3;
    int retryDelay = 500;
    for (int i = 0; i < maxRetries; i++) {
      try (Socket socket = new Socket(host, port);
          PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
        out.println("REFRESH");
        if (out.checkError()) {
          throw new IOException("Write failed");
        }
        Logger.info("[GatewayNotifier] Sent REFRESH to gateway on port " + port);
        return;
      } catch (IOException e) {
        Logger.warn("[GatewayNotifier] Attempt " + (i + 1) + " failed: " + e.getMessage());
        if (i < maxRetries - 1) {
          try {
            Thread.sleep(retryDelay);
          } catch (InterruptedException ignored) {
          }
        } else {
          Logger.error(
              "[GatewayNotifier] Failed to send REFRESH after " + maxRetries + " attempts");
        }
      }
    }
  }

  public void close() {
    // 无需关闭任何资源，每次都是新建连接
  }
}
