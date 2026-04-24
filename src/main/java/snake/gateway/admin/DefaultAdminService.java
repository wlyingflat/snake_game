package snake.gateway.admin;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;
import snake.game.notification.IGameClientNotifier;
import snake.game.room.Room;
import snake.game.room.RoomManager;

public class DefaultAdminService implements AdminService {
  private final RoomManager roomManager;
  private final IGameClientNotifier notifier;
  private final ILogger logger = Logger.getInstance();
  private ServerSocket serverSocket;
  private volatile boolean running = true;
  private final ExecutorService adminPool =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r);
            t.setName("gateway-admin-" + t.getId());
            t.setDaemon(true);
            return t;
          });

  public DefaultAdminService(RoomManager roomManager, IGameClientNotifier notifier) {
    this.roomManager = roomManager;
    this.notifier = notifier;
  }

  @Override
  public void start() {
    new Thread(
            () -> {
              try {
                serverSocket = new ServerSocket(Config.GATEWAY_ADMIN_PORT);
                logger.info("Gateway admin server listening on port " + Config.GATEWAY_ADMIN_PORT);
                while (running) {
                  Socket client = serverSocket.accept();
                  adminPool.submit(() -> handleCommand(client));
                }
              } catch (IOException e) {
                if (running) logger.error("Admin server error: " + e.getMessage());
              }
            })
        .start();
  }

  private void handleCommand(Socket socket) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      String line = in.readLine();
      if (line == null) return;
      String[] parts = line.split(" ");
      if (parts[0].equals("CREATE_ROOM") && parts.length >= 2) {
        int roomId = Integer.parseInt(parts[1]);
        Room room = roomManager.createRoom(roomId, notifier, null);
        out.println(room != null ? "OK" : "ERROR");
      } else {
        out.println("UNKNOWN");
      }
    } catch (IOException e) {
      logger.error("Admin command error: " + e.getMessage());
    }
  }

  @Override
  public void stop() {
    running = false;
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {
    }
    adminPool.shutdown();
    try {
      adminPool.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }
  }
}
