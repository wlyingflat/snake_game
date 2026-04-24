package snake.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import snake.common.*;
import snake.core.*;
import snake.util.Logger;

public class Gateway extends NioServer {
  private final ConcurrentHashMap<SocketChannel, ClientSession> sessions =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ClientSession> usernameToSession =
      new ConcurrentHashMap<>();
  private RoomManager roomManager;
  private ServerSocket adminServer;
  private final ObjectMapper mapper = new ObjectMapper();

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
  protected void processMessage(NioSession session, String jsonMsg) {
    ClientSession client = (ClientSession) session;
    try {
      ObjectNode root = (ObjectNode) mapper.readTree(jsonMsg);
      String cmd = root.get("cmd").asText();

      switch (cmd) {
        case "USER":
          client.username = root.get("username").asText();
          usernameToSession.put(client.username, client);
          Logger.info("[Gateway] Client identified: " + client.username);
          // 发送房间列表
          client.enqueueResponse(buildRoomListJson());
          break;
        case "PING":
          client.enqueueResponse("{\"cmd\":\"PONG\"}");
          break;
        case "QUIT":
          if (client.roomId != -1) {
            roomManager.sendToRoom(client.roomId, new LeaveRoomMsg(client.username));
          }
          closeSession(client);
          break;
        case "JOIN":
          if (client.username == null) {
            client.enqueueResponse("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
            break;
          }
          int roomId = root.get("roomId").asInt();
          roomManager.sendToRoom(roomId, new JoinRoomMsg(client.username));
          break;
        case "CREATE":
          if (client.username == null) {
            client.enqueueResponse("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
            break;
          }
          int newRoomId = root.get("roomId").asInt();
          Logger.info("[Gateway] CREATE room " + newRoomId + " by " + client.username);
          boolean created = roomManager.createRoom(newRoomId);
          Logger.info("[Gateway] createRoom returned: " + created);
          if (created) {
            roomManager.sendToRoom(newRoomId, new JoinRoomMsg(client.username));
          } else {
            client.enqueueResponse("{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}");
          }
          break;
        case "INPUT":
          if (client.username == null) break;
          if (client.roomId != -1) {
            String dirStr = root.get("direction").asText();
            Direction dir = Direction.valueOf(dirStr);
            roomManager.sendToRoom(client.roomId, new InputMsg(client.username, dir));
          }
          break;
        case "ROOM_LIST":
          Logger.info("[Gateway] Received ROOM_LIST request from " + client.username);
          String roomListJson = buildRoomListJson();
          Logger.info("[Gateway] Sending room list: " + roomListJson);
          client.enqueueResponse(roomListJson);
          break;
        default:
          Logger.debug("[Gateway] Unhandled cmd: " + cmd);
      }
    } catch (Exception e) {
      Logger.error("[Gateway] Error processing message: " + e.getMessage());
      client.enqueueResponse("{\"cmd\":\"ERROR\",\"message\":\"Invalid message\"}");
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

  private String buildRoomListJson() {
    Logger.info("[Gateway] buildRoomListJson called");
    ArrayNode rooms = mapper.createArrayNode();
    String listData = roomManager.getRoomListDataOnly();
    Logger.info("[Gateway] Raw listData from RoomManager:\n" + listData);
    String[] lines = listData.split("\n");
    for (String line : lines) {
      if (line.trim().isEmpty() || line.startsWith("No active rooms")) continue;
      String[] parts = line.trim().split("\\s+");
      if (parts.length >= 5) {
        try {
          int id = Integer.parseInt(parts[0]);
          String status = parts[1];
          String[] playerMax = parts[2].split("/");
          int players = Integer.parseInt(playerMax[0]);
          int maxPlayers = Integer.parseInt(playerMax[1]);
          int port = Integer.parseInt(parts[3]);
          String createdAt = parts[4];
          ObjectNode room = mapper.createObjectNode();
          room.put("id", id);
          room.put("status", status);
          room.put("players", players);
          room.put("maxPlayers", maxPlayers);
          room.put("port", port);
          room.put("createdAt", createdAt);
          rooms.add(room);
          Logger.debug("[Gateway] Added room: id=" + id + ", status=" + status);
        } catch (Exception e) {
          Logger.warn("[Gateway] Failed to parse room line: " + line + " - " + e.getMessage());
        }
      } else {
        Logger.warn(
            "[Gateway] Line has insufficient parts: " + line + " (parts=" + parts.length + ")");
      }
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("cmd", "ROOM_LIST");
    response.set("rooms", rooms);
    try {
      String json = mapper.writeValueAsString(response);
      Logger.info("[Gateway] Final room list JSON: " + json);
      return json;
    } catch (Exception e) {
      return "{\"cmd\":\"ERROR\",\"message\":\"Cannot build room list\"}";
    }
  }

  private void checkHeartbeats() {
    long now = System.currentTimeMillis() / 1000;
    for (ClientSession session : sessions.values()) {
      if (session.pendingPong && (now - session.lastPingSent) > Config.HEARTBEAT_TIMEOUT) {
        Logger.warn("[Gateway] Client " + session.username + " heartbeat timeout");
        closeSession(session);
        continue;
      }
      if (!session.pendingPong && (now - session.lastHeartbeat) >= Config.HEARTBEAT_INTERVAL) {
        session.enqueueResponse("{\"cmd\":\"PING\"}");
        session.pendingPong = true;
        session.lastPingSent = now;
      }
    }
  }

  private void startAdminServer() {
    new Thread(
            () -> {
              try (ServerSocket server = new ServerSocket(Config.GATEWAY_ADMIN_PORT)) {
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
        out.print(roomManager.getRoomListDataOnly());
        out.flush();
      }
    } catch (IOException e) {
      Logger.error("Query handler error: " + e.getMessage());
    }
  }

  @Override
  public void start() throws IOException {
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

  private void broadcastRoomListToLobby() {
    String roomListJson = buildRoomListJson();
    for (ClientSession session : sessions.values()) {
      if (session.username != null && session.roomId == -1) {
        session.enqueueResponse(roomListJson);
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
