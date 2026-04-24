package snake.server;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.*;
import snake.util.*;

public class MainServer extends NioServer {
  private UserManager userManager;
  private RoomManager roomManager;
  private GatewayNotifier gatewayNotifier;
  private ServerSocket registerServer;
  private ServerSocket listQueryServer;
  private Map<SocketChannel, MainSession> sessions = new ConcurrentHashMap<>();

  public MainServer(int port, int gatewayPort) {
    super(port);
    this.userManager = new UserManager("users.txt");
    this.roomManager = new RoomManager();
    startGatewayProcess(gatewayPort);
    this.gatewayNotifier = new GatewayNotifier("localhost", Config.GATEWAY_NOTIFY_PORT);
  }

  @Override
  protected String getServerName() {
    return "MainServer";
  }

  @Override
  protected NioSession createSession(SocketChannel channel) {
    MainSession session = new MainSession(channel, this);
    sessions.put(channel, session);
    return session;
  }

  @Override
  protected void processMessage(NioSession session, String msg) {
    MainSession mainSession = (MainSession) session;
    String[] parts = msg.split(" ");
    String cmd = parts[0];
    try {
      switch (cmd) {
        case Protocol.CMD_REGISTER:
          if (parts.length >= 3) {
            String username = parts[1];
            String password = parts[2];
            if (userManager.register(username, password)) {
              mainSession.enqueueResponse(
                  Protocol.RESP_OK
                      + " Registration successful\nGATEWAY 127.0.0.1 "
                      + Config.GATEWAY_DEFAULT_PORT);
            } else {
              mainSession.enqueueResponse(Protocol.RESP_ERROR + " Registration failed");
            }
          } else {
            mainSession.enqueueResponse(Protocol.RESP_ERROR + " Invalid REG command");
          }
          break;
        case Protocol.CMD_LOGIN:
          if (parts.length >= 3) {
            String username = parts[1];
            String password = parts[2];
            if (userManager.login(username, password)) {
              mainSession.enqueueResponse(
                  Protocol.RESP_OK
                      + " Login successful\nGATEWAY 127.0.0.1 "
                      + Config.GATEWAY_DEFAULT_PORT);
            } else {
              mainSession.enqueueResponse(Protocol.RESP_ERROR + " Login failed");
            }
          } else {
            mainSession.enqueueResponse(Protocol.RESP_ERROR + " Invalid LOGIN command");
          }
          break;
        case Protocol.CMD_CREATE:
          if (parts.length >= 3) {
            int roomId = Integer.parseInt(parts[1]);
            String creator = parts[2];
            if (roomManager.createRoom(roomId, creator)) {
              mainSession.enqueueResponse(
                  Protocol.RESP_REDIRECT + " " + roomManager.getRoom(roomId).port + " " + roomId);
            } else {
              mainSession.enqueueResponse(Protocol.RESP_ERROR + " Cannot create room");
            }
          } else {
            mainSession.enqueueResponse(Protocol.RESP_ERROR + " Invalid CREATE command");
          }
          break;
        case Protocol.CMD_JOIN:
          if (parts.length >= 3) {
            int roomId = Integer.parseInt(parts[1]);
            String username = parts[2];
            if (roomManager.joinRoom(roomId, username)) {
              RoomInfo room = roomManager.getRoom(roomId);
              mainSession.enqueueResponse(Protocol.RESP_REDIRECT + " " + room.port + " " + roomId);
            } else {
              mainSession.enqueueResponse(Protocol.RESP_ERROR + " Cannot join room");
            }
          } else {
            mainSession.enqueueResponse(Protocol.RESP_ERROR + " Invalid JOIN command");
          }
          break;
        case Protocol.CMD_LOGOUT:
          if (parts.length >= 2) {
            String username = parts[1];
            userManager.logout(username);
            mainSession.enqueueResponse(Protocol.RESP_OK + " Logout successful");
          } else {
            mainSession.enqueueResponse(Protocol.RESP_ERROR + " Invalid LOGOUT command");
          }
          break;
        case Protocol.CMD_ROOM_LIST:
          mainSession.enqueueResponse(roomManager.getRoomList());
          break;
        default:
          mainSession.enqueueResponse(Protocol.RESP_ERROR + " Unknown command");
      }
    } catch (Exception e) {
      Logger.error("Error handling request: " + e.getMessage());
      mainSession.enqueueResponse(Protocol.RESP_ERROR + " Internal error");
    }
  }

  @Override
  protected void onSessionClosed(NioSession session) {
    sessions.remove(session.channel);
  }

  private void startGatewayProcess(int gatewayPort) {
    try {
      // 获取项目根目录（假设主服务器运行在项目根目录）
      String projectDir = System.getProperty("user.dir");
      ProcessBuilder pb =
          new ProcessBuilder(
              "mvn",
              "exec:java",
              "-Dexec.mainClass=snake.gateway.Gateway",
              "-Dexec.args=" + gatewayPort);
      pb.directory(new File(projectDir));
      pb.inheritIO();
      pb.start();
      Logger.info("Gateway process started on port " + gatewayPort);
      Thread.sleep(500); // 等待网关初始化
    } catch (IOException | InterruptedException e) {
      Logger.error("Failed to start gateway: " + e.getMessage());
    }
  }

  private void startRoomRegisterServer() throws IOException {
    registerServer = new ServerSocket(Config.ROOM_REGISTER_PORT);
    Logger.info("Room register server listening on port " + Config.ROOM_REGISTER_PORT);
    new Thread(
            () -> {
              while (running) {
                try {
                  Socket regSocket = registerServer.accept();
                  new Thread(() -> handleRoomRegistration(regSocket)).start();
                } catch (IOException e) {
                  if (running) Logger.error("Room register accept error: " + e.getMessage());
                }
              }
            })
        .start();
  }

  private void handleRoomRegistration(Socket socket) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      String line = in.readLine();
      if (line == null) return;
      Logger.info("[MainServer] Received from room register: " + line);
      String[] parts = line.split(" ");
      if (parts[0].equals("REGISTER")) {
        int roomId = Integer.parseInt(parts[1]);
        int port = Integer.parseInt(parts[2]);
        roomManager.registerRoomProcess(roomId, port);
        out.println("OK");
        Logger.info("Room " + roomId + " registered on port " + port);
        Logger.info("[MainServer] Notifying gateway to refresh room list (REGISTER)");
        gatewayNotifier.notifyRefresh();
      } else if (parts[0].equals("UNREGISTER")) {
        int roomId = Integer.parseInt(parts[1]);
        roomManager.unregisterRoom(roomId);
        out.println("OK");
        Logger.info("Room " + roomId + " unregistered");
        Logger.info("[MainServer] Notifying gateway to refresh room list (UNREGISTER)");
        gatewayNotifier.notifyRefresh();
      } else if (parts[0].equals("UPDATE")) {
        int roomId = Integer.parseInt(parts[1]);
        int playerCount = Integer.parseInt(parts[2]);
        int activePlayers = parts.length >= 4 ? Integer.parseInt(parts[3]) : 0;
        roomManager.updateRoomStatus(roomId, playerCount, activePlayers);
        out.println("OK");
        Logger.info(
            "Room "
                + roomId
                + " status updated: players="
                + playerCount
                + ", active="
                + activePlayers);
        Logger.info("[MainServer] Notifying gateway to refresh room list (UPDATE)");
        gatewayNotifier.notifyRefresh();
      } else {
        Logger.warn("[MainServer] Unknown command from room register: " + line);
      }
    } catch (IOException e) {
      Logger.error("Room registration error: " + e.getMessage());
    }
  }

  private void startRoomListQueryServer() throws IOException {
    listQueryServer = new ServerSocket(Config.ROOM_LIST_QUERY_PORT);
    Logger.info("Room list query server listening on port " + Config.ROOM_LIST_QUERY_PORT);
    new Thread(
            () -> {
              while (running) {
                try {
                  Socket socket = listQueryServer.accept();
                  new Thread(() -> handleRoomListQuery(socket)).start();
                } catch (IOException e) {
                  if (running) Logger.error("Room list query accept error: " + e.getMessage());
                }
              }
            })
        .start();
  }

  private void handleRoomListQuery(Socket socket) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      String cmd = in.readLine();
      if ("LIST".equals(cmd)) {
        String data = roomManager.getRoomListDataOnly();
        Logger.debug("[MainServer] Sending room list data to gateway:\n" + data);
        out.print(data);
        out.flush();
      }
    } catch (IOException e) {
      Logger.error("Room list query handler error: " + e.getMessage());
    }
  }

  @Override
  public void start() throws IOException {
    startRoomRegisterServer();
    startRoomListQueryServer();
    super.start();
  }

  @Override
  protected void cleanup() {
    running = false;
    try {
      if (registerServer != null) registerServer.close();
      if (listQueryServer != null) listQueryServer.close();
    } catch (IOException e) {
    }
    gatewayNotifier.close();
    userManager.save();
    roomManager.shutdown();
    super.cleanup();
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: MainServer <port> [gatewayPort]");
      System.exit(1);
    }
    int port = Integer.parseInt(args[0]);
    int gatewayPort = args.length >= 2 ? Integer.parseInt(args[1]) : Config.GATEWAY_DEFAULT_PORT;
    try {
      new MainServer(port, gatewayPort).start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
