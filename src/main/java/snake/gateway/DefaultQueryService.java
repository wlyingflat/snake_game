package snake.gateway;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.common.Config;
import snake.common.RoomListEntry;
import snake.common.RoomStatus;
import snake.core.RoomManager;
import snake.util.ILogger;
import snake.util.Logger;

public class DefaultQueryService implements QueryService {
  private final RoomManager roomManager;
  private final ILogger logger = Logger.getInstance();
  private ServerSocket serverSocket;
  private volatile boolean running = true;
  private final ExecutorService queryPool =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r);
            t.setName("gateway-query-" + t.getId());
            t.setDaemon(true);
            return t;
          });

  public DefaultQueryService(RoomManager roomManager) {
    this.roomManager = roomManager;
  }

  @Override
  public void start() {
    new Thread(
            () -> {
              try {
                serverSocket = new ServerSocket(Config.ROOM_LIST_QUERY_PORT);
                logger.info(
                    "Gateway query server listening on port " + Config.ROOM_LIST_QUERY_PORT);
                while (running) {
                  Socket client = serverSocket.accept();
                  queryPool.submit(() -> handleQuery(client));
                }
              } catch (IOException e) {
                if (running) logger.error("Query server error: " + e.getMessage());
              }
            })
        .start();
  }

  private void handleQuery(Socket socket) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      String cmd = in.readLine();
      if ("LIST".equals(cmd)) {
        StringBuilder sb = new StringBuilder();
        for (RoomListEntry entry : roomManager.getRoomList()) {
          sb.append(
              String.format(
                  "%d %s %d/%d\n",
                  entry.roomId,
                  entry.status == RoomStatus.OPEN ? "OPEN" : "FULL",
                  entry.playerCount,
                  Config.MAX_PLAYERS_PER_ROOM));
        }
        if (sb.length() == 0) sb.append("No active rooms.\n");
        out.print(sb.toString());
        out.flush();
      }
    } catch (IOException e) {
      logger.error("Query handler error: " + e.getMessage());
    }
  }

  @Override
  public void stop() {
    running = false;
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {
    }
    queryPool.shutdown();
    try {
      queryPool.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }
  }
}
