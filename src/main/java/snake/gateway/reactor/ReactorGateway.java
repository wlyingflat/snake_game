package snake.gateway.reactor;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.actor.EnhancedMessage;
import snake.base.*;
import snake.distributed.DistributedCoordinator;
import snake.gateway.auth.GatewayAuthClient;
import snake.gateway.dispatcher.MessageDispatcher;
import snake.gateway.heartbeat.HeartbeatService;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;
import snake.mq.MessageBus;
import snake.network.ISession;
import snake.network.NioServer;

/** Reactor 层 - 纯网络网关 只负责连接管理、认证、路由 不包含任何游戏逻辑 */
public class ReactorGateway extends NioServer {
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final MessageDispatcher dispatcher;
  private final GatewayAuthClient authClient;
  private final DistributedCoordinator coordinator;
  private final String gatewayId;
  private final ExecutorService workerPool;
  private final MessageBus messageBus;

  public ReactorGateway(
      int port,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      MessageDispatcher dispatcher,
      GatewayAuthClient authClient,
      DistributedCoordinator coordinator,
      MessageBus messageBus,
      String gatewayId) {
    super(port);
    this.sessionManager = sessionManager;
    this.heartbeatService = heartbeatService;
    this.dispatcher = dispatcher;
    this.authClient = authClient;
    this.coordinator = coordinator;
    this.messageBus = messageBus;
    this.gatewayId = gatewayId;

    final java.util.concurrent.atomic.AtomicLong counter =
        new java.util.concurrent.atomic.AtomicLong(0);
    this.workerPool =
        Executors.newCachedThreadPool(
            r -> {
              Thread t =
                  new Thread(r, "gateway-" + gatewayId + "-worker-" + counter.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  public String getGatewayId() {
    return gatewayId;
  }

  @Override
  protected String getServerName() {
    return "ReactorGateway-" + gatewayId;
  }

  @Override
  protected ISession createSession(SocketChannel channel) {
    ClientSession session = new ClientSession(channel, this);
    sessionManager.registerSession(session);
    heartbeatService.refresh(session);
    return session;
  }

  @Override
  protected void processMessage(ISession session, String jsonMsg) {
    workerPool.submit(
        () -> {
          try {
            ClientSession client = (ClientSession) session;
            JsonNode root = JsonUtils.MAPPER.readTree(jsonMsg);
            String cmd = root.get("cmd").asText();

            switch (cmd) {
              case "REGISTER":
                handleRegister(client, root);
                break;
              case "LOGIN":
              case "USER":
                handleLogin(client, root);
                break;
              case "LOGOUT":
                handleLogout(client, root);
                break;
              case "ROOM_LIST":
                handleRoomList(client);
                break;
              case "LEADERBOARD":
                handleLeaderboard(client, root);
                break;
              case "CREATE":
              case "JOIN":
              case "INPUT":
              case "LEAVE":
              case "QUIT":
                handleGameCommand(client, root);
                break;
              case "PING":
                client.sendMessage("{\"cmd\":\"PONG\"}");
                heartbeatService.refresh(client);
                break;
              case "PONG":
                client.pendingPong = false;
                client.lastHeartbeat = System.currentTimeMillis() / 1000;
                heartbeatService.refresh(client);
                break;
              default:
                client.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Unknown command\"}");
            }
          } catch (Exception e) {
            logger.error("Error processing message: " + e.getMessage());
          }
        });
  }

  private void handleRegister(ClientSession session, JsonNode params) {
    String username = params.get("username").asText();
    String password = params.get("password").asText();
    GatewayAuthClient.AuthResult result = authClient.register(username, password);
    if (result.success) {
      session.sendMessage("{\"cmd\":\"REGISTER_OK\"}");
    } else {
      session.sendMessage(
          "{\"cmd\":\"ERROR\",\"message\":\""
              + (result.message != null ? result.message : "Registration failed")
              + "\"}");
    }
  }

  private void handleLogin(ClientSession session, JsonNode params) {
    String username = params.get("username").asText();
    String password = params.get("password").asText();

    if (coordinator != null && coordinator.isOnline(username)) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"User already online\"}");
      return;
    }

    GatewayAuthClient.AuthResult result = authClient.login(username, password);
    if (result.success) {
      session.username = username;
      sessionManager.bindUsername(session.getSessionId(), username);
      if (coordinator != null) {
        coordinator.markOnline(username);
        coordinator.setPlayerLocation(username, gatewayId, -1);
      }
      String loginOk =
          String.format(
              "{\"cmd\":\"LOGIN_OK\",\"gatewayHost\":\"%s\",\"gatewayPort\":%d}",
              Config.GATEWAY_HOST, Config.GATEWAY_PORT);
      session.sendMessage(loginOk);
      sendRoomList(session);
      heartbeatService.refresh(session);
      logger.info("User logged in: " + username);
    } else {
      session.sendMessage(
          "{\"cmd\":\"ERROR\",\"message\":\""
              + (result.message != null ? result.message : "Login failed")
              + "\"}");
    }
  }

  private void handleLogout(ClientSession session, JsonNode params) {
    if (session.username != null) {
      authClient.logout(session.username);
      if (coordinator != null) {
        coordinator.markOffline(session.username);
        coordinator.removePlayerLocation(session.username);
      }
    }
    sessionManager.unbindUsername(session.username);
    sessionManager.removeSession(session.getSessionId());
    session.close();
  }

  private void handleRoomList(ClientSession session) {
    sendRoomList(session);
    heartbeatService.refresh(session);
  }

  public void sendRoomList(ClientSession session) {
    var rooms = JsonUtils.MAPPER.createArrayNode();
    if (coordinator != null) {
      for (var entry : coordinator.getAllRooms()) {
        var room = JsonUtils.MAPPER.createObjectNode();
        room.put("id", entry.roomId());
        room.put("status", "FULL".equals(entry.status()) ? "FULL" : "OPEN");
        room.put("players", entry.playerCount());
        room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
        rooms.add(room);
      }
    }
    var resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "ROOM_LIST");
    resp.set("rooms", rooms);
    try {
      session.sendMessage(JsonUtils.MAPPER.writeValueAsString(resp));
    } catch (Exception e) {
      session.sendMessage("{\"cmd\":\"ERROR\"}");
    }
  }

  public void sendRoomListToLobby() {
    try {
      String roomListJson = buildRoomListJson();
      for (ClientSession s : sessionManager.getAllSessions()) {
        if (s != null && !s.closed && s.username != null && s.roomId == -1) {
          s.sendMessage(roomListJson);
        }
      }
    } catch (Exception e) {
      logger.error("Error broadcasting room list: " + e.getMessage());
    }
  }

  private String buildRoomListJson() {
    var rooms = JsonUtils.MAPPER.createArrayNode();
    if (coordinator != null) {
      for (var entry : coordinator.getAllRooms()) {
        var room = JsonUtils.MAPPER.createObjectNode();
        room.put("id", entry.roomId());
        room.put("status", "FULL".equals(entry.status()) ? "FULL" : "OPEN");
        room.put("players", entry.playerCount());
        room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
        rooms.add(room);
      }
    }
    var resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "ROOM_LIST");
    resp.set("rooms", rooms);
    try {
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{}";
    }
  }

  private void handleLeaderboard(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int limit = params.has("limit") ? params.get("limit").asInt() : 10;
    var ranks = coordinator.getLeaderboard(limit);
    var resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "LEADERBOARD");
    var entries = JsonUtils.MAPPER.createArrayNode();
    for (var rank : ranks) {
      var entry = JsonUtils.MAPPER.createObjectNode();
      entry.put("rank", rank.rank);
      entry.put("username", rank.username);
      entry.put("score", rank.score);
      entries.add(entry);
    }
    resp.set("leaderboard", entries);
    try {
      session.sendMessage(JsonUtils.MAPPER.writeValueAsString(resp));
    } catch (Exception e) {
      session.sendMessage("{\"cmd\":\"ERROR\"}");
    }
    heartbeatService.refresh(session);
  }

  private void handleGameCommand(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    dispatcher.routeToWorker(session.username, params);
    heartbeatService.refresh(session);
  }

  @Override
  protected void onSessionClosed(ISession session) {
    ClientSession client = (ClientSession) session;
    client.closed = true;
    heartbeatService.remove(client);
    if (client.username != null) {
      if (client.roomId != -1 && coordinator != null && messageBus != null) {
        // 获取该房间所在的 Worker
        String workerId = coordinator.getRoomWorker(client.roomId);
        if (workerId != null) {
          // 构造 LEAVE 消息，通过 RabbitMQ 发送给 Worker
          EnhancedMessage leaveMsg =
              new EnhancedMessage("LEAVE", client.username, client.roomId, gatewayId, "{}");
          messageBus.sendToWorker(workerId, leaveMsg.toJson());
          logger.info(
              "Sent LEAVE for "
                  + client.username
                  + "from disconnected session to worker "
                  + workerId);
        }
      }
      sessionManager.unbindUsername(client.username);
      if (coordinator != null) {
        coordinator.markOffline(client.username);
        coordinator.removePlayerLocation(client.username);
      }
    }
    sessionManager.removeSession(session.getSessionId());
  }

  @Override
  public void start() throws IOException {
    if (coordinator != null) coordinator.registerGateway(gatewayId);
    heartbeatService.start();
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    heartbeatService.stop();
    workerPool.shutdown();
    try {
      workerPool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      workerPool.shutdownNow();
    }
    if (coordinator != null) coordinator.unregisterGateway(gatewayId);
  }
}
