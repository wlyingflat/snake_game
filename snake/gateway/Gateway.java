// snake/gateway/Gateway.java
package snake.gateway;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import snake.common.*;
import snake.core.*;
import snake.util.*;

public class Gateway extends NioServer {
  private final ConcurrentHashMap<SocketChannel, ClientSession> sessions =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ClientSession> usernameToSession =
      new ConcurrentHashMap<>();
  private RoomManager roomManager;
  private ServerSocket adminServer; // 接收 MainServer 的创建房间命令

  public Gateway(int port) {
    super(port);
  }

  @Override
  protected String getServerName() {
    return "Gateway";
  }

  @Override
  protected NioSession createSession(SocketChannel channel) {
    ClientSession session = new ClientSession(channel, this);
    sessions.put(channel, session);
    Logger.debug("[Gateway] New session created, total sessions: " + sessions.size());
    return session;
  }

  @Override
  protected void processMessage(NioSession session, String msg) {
    ClientSession client = (ClientSession) session;
    long now = System.currentTimeMillis() / 1000;
    client.lastHeartbeat = now;
    client.pendingPong = false;

    try {
      if (msg.startsWith(Protocol.USER + " ")) {
        client.username = msg.substring(5);
        usernameToSession.put(client.username, client);
        Logger.info("[Gateway] Client identified: " + client.username);
        // 发送房间列表
        String roomList = roomManager.getRoomListDataOnly();
        client.enqueueResponse(Protocol.ROOM_LIST_UPDATE + "|" + roomList);
      } else if (msg.equals(Protocol.PING)) {
        client.enqueueResponse(Protocol.PONG);
      } else if (msg.equals(Protocol.PONG)) {
        // ignore
      } else if (msg.equals(Protocol.QUIT)) {
        // 离开房间
        if (client.roomId != -1) {
          roomManager.sendToRoom(client.roomId, new LeaveRoomMsg(client.username));
        }
        closeSession(client);
      } else if (msg.startsWith(Protocol.CMD_JOIN + " ")) {
        int roomId = Integer.parseInt(msg.split(" ")[1]);
        roomManager.sendToRoom(roomId, new JoinRoomMsg(client.username));
      } else if (msg.startsWith(Protocol.CMD_CREATE + " ")) {
        int roomId = Integer.parseInt(msg.split(" ")[1]);
        if (roomManager.createRoom(roomId)) {
          roomManager.sendToRoom(roomId, new JoinRoomMsg(client.username));
        } else {
          client.enqueueResponse(Protocol.RESP_ERROR + " Cannot create room");
        }
      } else if (msg.length() == 1 && "wasd".contains(msg.toLowerCase())) {
        if (client.roomId != -1) {
          Direction dir =
              switch (msg.toLowerCase().charAt(0)) {
                case 'w' -> Direction.UP;
                case 's' -> Direction.DOWN;
                case 'a' -> Direction.LEFT;
                case 'd' -> Direction.RIGHT;
                default -> null;
              };
          if (dir != null) {
            Logger.debug("[Gateway] Forwarding direction " + dir + " to room " + client.roomId);
            roomManager.sendToRoom(client.roomId, new InputMsg(client.username, dir));
          }
        } else {
          Logger.debug("[Gateway] Ignored direction from " + client.username + " (not in room)");
        }
      } else if (msg.equals(Protocol.CMD_ROOM_LIST)) {
        String list = roomManager.getRoomListDataOnly();
        client.enqueueResponse(Protocol.ROOM_LIST_UPDATE + "|" + list);
      } else {
        Logger.debug("[Gateway] Unhandled message: " + msg);
      }
    } catch (Exception e) {
      Logger.error("[Gateway] Error processing message: " + e.getMessage());
      closeSession(client);
    }
  }

  @Override
  protected void onSessionClosed(NioSession session) {
    ClientSession client = (ClientSession) session;
    if (client.username != null) {
      usernameToSession.remove(client.username);
    }
    if (client.roomId != -1) {
      roomManager.sendToRoom(client.roomId, new LeaveRoomMsg(client.username));
    }
    sessions.remove(client.channel);
    Logger.debug("[Gateway] Session closed, remaining sessions: " + sessions.size());
  }

  private void checkHeartbeats() {
    long now = System.currentTimeMillis() / 1000;
    for (ClientSession session : sessions.values()) {
      if (session.pendingPong && (now - session.lastPingSent) > Config.HEARTBEAT_TIMEOUT) {
        Logger.warn("[Gateway] Client " + session.username + " heartbeat timeout (no PONG)");
        closeSession(session);
        continue;
      }

      if (!session.pendingPong && (now - session.lastHeartbeat) >= Config.HEARTBEAT_INTERVAL) {
        session.enqueueResponse(Protocol.PING);
        session.pendingPong = true;
        session.lastPingSent = now;
        Logger.debug("[Gateway] Sent PING to " + session.username);
      }
    }
  }

  // 接收 MainServer 的创建房间命令
  private void startAdminServer() {
    new Thread(
            () -> {
              try (ServerSocket server = new ServerSocket(19004)) {
                Logger.info("Gateway admin server listening on port 19004");
                while (running) {
                  Socket client = server.accept();
                  new Thread(() -> handleAdminCommand(client)).start();
                }
              } catch (IOException e) {
                if (running) Logger.error("Admin server error: " + e.getMessage());
              }
            })
        .start();
  }

  private void handleAdminCommand(Socket socket) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      String line = in.readLine();
      if (line == null) return;
      String[] parts = line.split(" ");
      if (parts[0].equals("CREATE_ROOM")) {
        int roomId = Integer.parseInt(parts[1]);
        boolean success = roomManager.createRoom(roomId);
        out.println(success ? "OK" : "ERROR");
      }
    } catch (IOException e) {
      Logger.error("Admin command error: " + e.getMessage());
    }
  }

  // 提供房间列表查询服务（供 MainServer 使用）
  private void startQueryServer() {
    new Thread(
            () -> {
              try (ServerSocket server = new ServerSocket(Config.ROOM_LIST_QUERY_PORT)) {
                Logger.info(
                    "Gateway query server listening on port " + Config.ROOM_LIST_QUERY_PORT);
                while (running) {
                  Socket client = server.accept();
                  new Thread(() -> handleQuery(client)).start();
                }
              } catch (IOException e) {
                if (running) Logger.error("Query server error: " + e.getMessage());
              }
            })
        .start();
  }

  private void handleQuery(Socket socket) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      String cmd = in.readLine();
      if ("LIST".equals(cmd)) {
        String data = roomManager.getRoomListDataOnly();
        out.print(data);
        out.flush();
      }
    } catch (IOException e) {
      Logger.error("Query handler error: " + e.getMessage());
    }
  }

  @Override
  public void start() throws IOException {
    // 创建 RoomManager 时传入回调，用于广播房间列表更新
    roomManager = new RoomManager(usernameToSession, this::broadcastRoomListToLobby);
    startAdminServer();
    startQueryServer();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    heartbeatExecutor.scheduleAtFixedRate(
        this::checkHeartbeats,
        Config.HEARTBEAT_INTERVAL,
        Config.HEARTBEAT_INTERVAL,
        TimeUnit.SECONDS);
    super.start();
    heartbeatExecutor.shutdown();
  }

  /** 广播房间列表给所有在大厅（未加入房间）的客户端 */
  private void broadcastRoomListToLobby() {
    String roomList = roomManager.getRoomListDataOnly();
    for (ClientSession session : sessions.values()) {
      if (session.username != null && session.roomId == -1) {
        session.enqueueResponse(Protocol.ROOM_LIST_UPDATE + "|" + roomList);
      }
    }
  }

  @Override
  protected void cleanup() {
    running = false;
    roomManager.shutdown();
    super.cleanup();
  }

  public static void main(String[] args) {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : Config.GATEWAY_DEFAULT_PORT;
    try {
      new Gateway(port).start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
