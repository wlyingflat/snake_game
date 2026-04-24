// snake/gateway/Gateway.java
package snake.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.common.*;
import snake.core.*;
import snake.util.Logger;

public class Gateway extends NioServer implements MessageDispatcher {

  private final ConcurrentHashMap<SocketChannel, ClientSession> sessions =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ClientSession> usernameToSession =
      new ConcurrentHashMap<>();
  private RoomManager roomManager;
  private ServerSocket adminServer;
  private final ObjectMapper mapper = new ObjectMapper();

  private final HashedWheelTimer heartbeatTimer =
      new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
  private final ConcurrentHashMap<ClientSession, Timeout> sessionTimeouts =
      new ConcurrentHashMap<>();

  private final ExecutorService workerPool =
      new ThreadPoolExecutor(
          Runtime.getRuntime().availableProcessors() * 2,
          Runtime.getRuntime().availableProcessors() * 2,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(10000),
          r -> {
            Thread t = new Thread(r);
            t.setName("gateway-worker-" + t.getId());
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.CallerRunsPolicy());

  private final ExecutorService adminPool =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r);
            t.setName("gateway-admin-" + t.getId());
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService queryPool =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r);
            t.setName("gateway-query-" + t.getId());
            t.setDaemon(true);
            return t;
          });

  private ScheduledExecutorService pingSender;
  private final AtomicBoolean runningFlag = new AtomicBoolean(true);

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
      var root = mapper.readTree(jsonMsg);
      String cmd = root.get("cmd").asText();
      if ("PING".equals(cmd)) {
        client.enqueueResponse("{\"cmd\":\"PONG\"}");
        refreshHeartbeatTimeout(client);
        return;
      } else if ("PONG".equals(cmd)) {
        client.pendingPong = false;
        client.lastHeartbeat = System.currentTimeMillis() / 1000;
        refreshHeartbeatTimeout(client);
        return;
      }
    } catch (Exception e) {
      // ignore
    }
    workerPool.submit(() -> handleBusiness(client, jsonMsg));
  }

  private void handleBusiness(ClientSession client, String jsonMsg) {
    try {
      var root = mapper.readTree(jsonMsg);
      String cmd = root.get("cmd").asText();

      switch (cmd) {
        case "USER":
          client.username = root.get("username").asText();
          usernameToSession.put(client.username, client);
          Logger.info("[Gateway] Client identified: " + client.username);
          client.enqueueResponse(buildRoomListJson());
          refreshHeartbeatTimeout(client);
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
          refreshHeartbeatTimeout(client);
          break;
        case "CREATE":
          if (client.username == null) {
            client.enqueueResponse("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
            break;
          }
          int newRoomId = root.get("roomId").asInt();
          if (roomManager.createRoom(newRoomId)) {
            roomManager.sendToRoom(newRoomId, new JoinRoomMsg(client.username));
          } else {
            client.enqueueResponse("{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}");
          }
          refreshHeartbeatTimeout(client);
          break;
        case "INPUT":
          if (client.username == null) break;
          if (client.roomId != -1) {
            String dirStr = root.get("direction").asText();
            Direction dir = Direction.valueOf(dirStr);
            roomManager.sendToRoom(client.roomId, new InputMsg(client.username, dir));
          }
          refreshHeartbeatTimeout(client);
          break;
        case "ROOM_LIST":
          client.enqueueResponse(buildRoomListJson());
          refreshHeartbeatTimeout(client);
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

  private void refreshHeartbeatTimeout(ClientSession session) {
    if (session.closed) return;
    Timeout old = sessionTimeouts.remove(session);
    if (old != null) old.cancel();
    Timeout newTimeout =
        heartbeatTimer.newTimeout(
            timeout -> onHeartbeatTimeout(session), Config.HEARTBEAT_TIMEOUT, TimeUnit.SECONDS);
    sessionTimeouts.put(session, newTimeout);
  }

  private void onHeartbeatTimeout(ClientSession session) {
    if (session.closed) return;
    Logger.warn("[Gateway] Client " + session.username + " heartbeat timeout, closing session");
    closeSession(session);
    sessionTimeouts.remove(session);
  }

  private void startPingSender() {
    pingSender =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("gateway-ping-sender");
              t.setDaemon(true);
              return t;
            });
    pingSender.scheduleAtFixedRate(
        () -> {
          if (!runningFlag.get()) return;
          long nowSec = System.currentTimeMillis() / 1000;
          for (ClientSession session : sessions.values()) {
            if (session.username == null || session.closed) continue;
            if (!session.pendingPong
                && (nowSec - session.lastHeartbeat) >= Config.HEARTBEAT_INTERVAL) {
              session.enqueueResponse("{\"cmd\":\"PING\"}");
              session.pendingPong = true;
              session.lastPingSent = nowSec;
              refreshHeartbeatTimeout(session);
            }
          }
        },
        Config.HEARTBEAT_INTERVAL,
        Config.HEARTBEAT_INTERVAL,
        TimeUnit.SECONDS);
  }

  @Override
  protected void onSessionClosed(NioSession session) {
    ClientSession client = (ClientSession) session;
    client.closed = true;
    Timeout timeout = sessionTimeouts.remove(client);
    if (timeout != null) timeout.cancel();

    if (client.username != null) {
      usernameToSession.remove(client.username);
      onUserDisconnected(client.username);
    }
    if (client.roomId != -1) {
      roomManager.sendToRoom(client.roomId, new LeaveRoomMsg(client.username));
    }
    sessions.remove(client.channel);
    Logger.debug("[Gateway] Session closed, remaining sessions: " + sessions.size());
  }

  // ========== MessageDispatcher 接口实现 ==========
  @Override
  public void sendToUser(String username, String message) {
    ClientSession session = usernameToSession.get(username);
    if (session != null && !session.closed) {
      try {
        var root = mapper.readTree(message);
        String cmd = root.get("cmd").asText();
        if ("JOIN_OK".equals(cmd)) {
          int roomId = root.get("roomId").asInt();
          session.roomId = roomId;
          Logger.debug("Updated session " + username + " roomId to " + roomId);
        } else if ("YOU_DIED".equals(cmd)) {
          session.roomId = -1;
          Logger.debug("Reset session " + username + " roomId to -1 (player died)");
        } else if ("JOIN_FAIL".equals(cmd)) {
          session.roomId = -1;
        }
      } catch (Exception e) {
        // ignore
      }
      session.enqueueResponse(message);
      refreshHeartbeatTimeout(session);
    } else if (session == null) {
      Logger.warn("Cannot send message to " + username + ": session not found");
    } else {
      Logger.warn("Cannot send message to " + username + ": session closed");
    }
  }

  @Override
  public void sendToUsers(Collection<String> usernames, String message) {
    for (String username : usernames) {
      sendToUser(username, message);
    }
  }

  @Override
  public void onUserDisconnected(String username) {
    // 可扩展通知 RoomManager 清理
  }

  // ========== 构建轻量级房间列表 JSON ==========
  private String buildRoomListJson() {
    ArrayNode rooms = mapper.createArrayNode();
    List<RoomListEntry> entries = roomManager.getRoomListForClient();
    for (RoomListEntry entry : entries) {
      ObjectNode room = mapper.createObjectNode();
      room.put("id", entry.roomId);
      room.put("status", entry.status == RoomStatus.OPEN ? "OPEN" : "FULL");
      room.put("players", entry.playerCount);
      room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
      rooms.add(room);
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("cmd", "ROOM_LIST");
    response.set("rooms", rooms);
    try {
      return mapper.writeValueAsString(response);
    } catch (Exception e) {
      return "{\"cmd\":\"ERROR\",\"message\":\"Cannot build room list\"}";
    }
  }

  private void startAdminServer() {
    new Thread(
            () -> {
              try (ServerSocket server = new ServerSocket(Config.GATEWAY_ADMIN_PORT)) {
                Logger.info("Gateway admin server listening on port 19004");
                while (running) {
                  Socket client = server.accept();
                  adminPool.submit(() -> handleAdminCommand(client));
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
                  queryPool.submit(() -> handleQuery(client));
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
    this.roomManager = new RoomManager(this, this::broadcastRoomListToLobby);
    startAdminServer();
    startQueryServer();
    startPingSender();
    super.start();
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
    runningFlag.set(false);
    running = false;
    if (pingSender != null) {
      pingSender.shutdownNow();
    }
    heartbeatTimer.stop();

    workerPool.shutdown();
    try {
      if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
        workerPool.shutdownNow();
      }
    } catch (InterruptedException e) {
      workerPool.shutdownNow();
      Thread.currentThread().interrupt();
    }
    adminPool.shutdown();
    queryPool.shutdown();
    try {
      adminPool.awaitTermination(1, TimeUnit.SECONDS);
      queryPool.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
