package snake.application.gateway;

import snake.application.gateway.handler.CommandDispatcher;
import snake.application.gateway.handler.GameCommandHandler;
import snake.application.gateway.handler.LeaderboardHandler;
import snake.application.gateway.handler.LoginHandler;
import snake.application.gateway.handler.LogoutHandler;
import snake.application.gateway.handler.RegisterHandler;
import snake.application.gateway.handler.RoomListHandler;
import snake.application.gateway.heartbeat.HeartbeatService;
import snake.application.gateway.lobby.LobbyService;
import snake.application.gateway.server.GatewayServer;
import snake.application.gateway.session.ClientSession;
import snake.application.gateway.session.SessionManager;
import snake.common.Config;
import snake.common.IServer;
import snake.common.JsonUtils;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.auth.GatewayAuthClient;
import snake.infrastructure.messaging.MessageBus;

public class ReactorGateway implements IServer {
  private final GatewayServer server;
  private final HeartbeatService heartbeatService;
  private final DistributedCoordinator coordinator;
  private final String gatewayId;
  private final LobbyService lobbyService;

  public ReactorGateway(
      int port,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      GatewayAuthClient authClient,
      DistributedCoordinator coordinator,
      MessageBus messageBus,
      String gatewayId) {
    this.heartbeatService = heartbeatService;
    this.coordinator = coordinator;
    this.gatewayId = gatewayId;

    // 1. 创建 CommandDispatcher
    CommandDispatcher dispatcher = new CommandDispatcher();
    dispatcher.register("REGISTER", new RegisterHandler(authClient));
    dispatcher.register(
        "USER",
        new LoginHandler(
            authClient,
            sessionManager,
            coordinator,
            gatewayId,
            this::sendRoomList,
            heartbeatService));
    dispatcher.register(
        "LOGIN",
        new LoginHandler(
            authClient,
            sessionManager,
            coordinator,
            gatewayId,
            this::sendRoomList,
            heartbeatService));
    dispatcher.register(
        "LOGOUT",
        new LogoutHandler(authClient, sessionManager, coordinator, messageBus, gatewayId));
    dispatcher.register("ROOM_LIST", new RoomListHandler(coordinator));
    dispatcher.register("LEADERBOARD", new LeaderboardHandler(coordinator));

    MessageDispatcher msgDispatcher = new MessageDispatcher(coordinator, messageBus, gatewayId);
    GameCommandHandler gameHandler = new GameCommandHandler(msgDispatcher);
    dispatcher.register("CREATE", gameHandler);
    dispatcher.register("JOIN", gameHandler);
    dispatcher.register("INPUT", gameHandler);
    dispatcher.register("LEAVE", gameHandler);
    dispatcher.register("QUIT", gameHandler);

    // 2. 创建 LobbyService
    this.lobbyService = new LobbyService(sessionManager, coordinator);

    // 3. 创建 GatewayServer
    this.server =
        new GatewayServer(
            port, sessionManager, heartbeatService, dispatcher, gatewayId, coordinator, messageBus);
  }

  @Override
  public void start() throws Exception {
    if (coordinator != null) coordinator.registerGateway(gatewayId);
    heartbeatService.start();
    server.start();
  }

  @Override
  public void stop() {
    server.stop();
    heartbeatService.stop();
    if (coordinator != null) coordinator.unregisterGateway(gatewayId);
  }

  // 供 LoginHandler 回调使用
  public void sendRoomList(ClientSession session) {
    // 委托给 LobbyService 的单人版本（或直接复用已有逻辑）
    // 简单实现：直接调用 RoomListHandler 的相同逻辑，但为了集中管理，我们在 RoomListHandler 里暴露一个静态方法或通过 LobbyService
    // 提供单用户发送。
    // 这里直接在内部构建 JSON 并发送
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
    lobbyService.sendRoomListToLobby();
  }

  public String getGatewayId() {
    return gatewayId;
  }
}
