// snake/server/MainServer.java
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
  private Map<SocketChannel, MainSession> sessions = new ConcurrentHashMap<>();

  public MainServer(int port, int gatewayPort) {
    super(port);
    this.userManager = new UserManager("users.txt");
    startGatewayProcess(gatewayPort);
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
            if (sendCreateRoomToGateway(roomId)) {
              // 返回房间ID（端口不再需要）
              mainSession.enqueueResponse(Protocol.RESP_REDIRECT + " " + roomId + " " + roomId);
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
            // 直接返回网关地址，房间ID用于后续加入
            mainSession.enqueueResponse(
                Protocol.RESP_REDIRECT + " " + Config.GATEWAY_DEFAULT_PORT + " " + roomId);
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
          mainSession.enqueueResponse(fetchRoomListFromGateway());
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

  private boolean sendCreateRoomToGateway(int roomId) {
    try (Socket socket = new Socket("localhost", 19004);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
      out.println("CREATE_ROOM " + roomId);
      String resp = in.readLine();
      return "OK".equals(resp);
    } catch (IOException e) {
      Logger.error("Failed to contact Gateway: " + e.getMessage());
      return false;
    }
  }

  private String fetchRoomListFromGateway() {
    try (Socket socket = new Socket("localhost", Config.ROOM_LIST_QUERY_PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
      out.println("LIST");
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null) {
        sb.append(line).append("\n");
      }
      return sb.toString();
    } catch (IOException e) {
      Logger.error("Failed to fetch room list from Gateway: " + e.getMessage());
      return "No active rooms.\n";
    }
  }

  private void startGatewayProcess(int gatewayPort) {
    try {
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

  @Override
  public void start() throws IOException {
    super.start();
  }

  @Override
  protected void cleanup() {
    running = false;
    userManager.save();
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
