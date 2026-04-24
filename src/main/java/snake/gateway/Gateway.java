package snake.gateway;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import snake.base.Config;
import snake.base.IConfigProvider;
import snake.base.ILogger;
import snake.base.JsonUtils;
import snake.base.Logger;
import snake.game.event.LeaveRoomMsg;
import snake.game.notification.IGameClientNotifier;
import snake.game.notification.SessionBasedNotifier;
import snake.game.room.Room;
import snake.game.room.RoomManager;
import snake.game.state.RoomStatus;
import snake.gateway.admin.AdminService;
import snake.gateway.admin.DefaultAdminService;
import snake.gateway.admin.DefaultQueryService;
import snake.gateway.admin.QueryService;
import snake.gateway.command.CommandDispatcher;
import snake.gateway.command.DefaultMessageRouter;
import snake.gateway.command.GameCommandHandler;
import snake.gateway.command.MessageRouter;
import snake.gateway.heartbeat.DefaultHeartbeatService;
import snake.gateway.heartbeat.HeartbeatService;
import snake.gateway.session.ClientSession;
import snake.gateway.session.DefaultSessionManager;
import snake.gateway.session.SessionManager;
import snake.network.ISession;
import snake.network.NioServer;
import snake.persistence.DatabaseManager;
import snake.persistence.PropertiesConfigProvider.PropertiesConfigProvider;
import snake.persistence.leaderboard.MySQLLeaderboardRepository;

public class Gateway extends NioServer {
  private final MessageRouter messageRouter;
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final AdminService adminService;
  private final QueryService queryService;
  private final RoomManager roomManager;
  private final ExecutorService workerPool;

  public Gateway(
      int port,
      RoomManager roomManager,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      AdminService adminService,
      QueryService queryService,
      MessageRouter messageRouter) {
    super(port);
    this.roomManager = roomManager;
    this.sessionManager = sessionManager;
    this.heartbeatService = heartbeatService;
    this.adminService = adminService;
    this.queryService = queryService;
    this.messageRouter = messageRouter;
    this.workerPool =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r);
              t.setName("gateway-worker-" + t.getId());
              t.setDaemon(true);
              return t;
            });
  }

  @Override
  protected String getServerName() {
    return "Gateway";
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
    workerPool.submit(() -> messageRouter.route(session, jsonMsg));
  }

  @Override
  protected void onSessionClosed(ISession session) {
    ClientSession client = (ClientSession) session;
    client.closed = true;

    // 移除心跳跟踪，防止内存泄漏
    heartbeatService.remove(client);

    if (client.username != null) {
      sessionManager.unbindUsername(client.username);
    }
    if (client.roomId != -1) {
      logger.debug("Closing session for " + client.username + ", was in room " + client.roomId);
      Room room = roomManager.getRoom(client.roomId);
      if (room != null) room.post(new LeaveRoomMsg(client.username));
      client.roomId = -1;
    }
    sessionManager.removeSession(session.getSessionId());
  }

  @Override
  public void start() throws IOException {
    adminService.start();
    queryService.start();
    heartbeatService.start();
    super.start();
  }

  @Override
  protected void cleanup() {
    running = false;
    heartbeatService.stop();
    adminService.stop();
    queryService.stop();
    workerPool.shutdown();
    try {
      workerPool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      workerPool.shutdownNow();
      Thread.currentThread().interrupt();
    }
    super.cleanup();
  }

  public static void main(String[] args) {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : Config.GATEWAY_DEFAULT_PORT;

    SessionManager sessionManager = new DefaultSessionManager();
    HeartbeatService heartbeatService = new DefaultHeartbeatService(session -> session.close());
    RoomManager roomManager = new RoomManager(null, null);
    IConfigProvider config = new PropertiesConfigProvider("config.properties");
    DatabaseManager dbManager = DatabaseManager.getInstance(config);
    DataSource dataSource = dbManager.getDataSource();
    MySQLLeaderboardRepository leaderboardRepo = new MySQLLeaderboardRepository(dataSource);
    IGameClientNotifier notifier = new SessionBasedNotifier(sessionManager, leaderboardRepo);
    roomManager.setNotifier(notifier);
    roomManager.setRoomListUpdateCallback(
        () -> {
          try {
            String roomListJson = buildRoomListJson(roomManager);
            int broadcastCount = 0;
            int skippedCount = 0;
            ILogger logger = Logger.getInstance();
            logger.info("Room list update callback invoked");
            for (ClientSession s : sessionManager.getAllSessions()) {
              if (s.username == null) {
                skippedCount++;
                continue;
              }
              if (s.roomId == -1) {
                s.sendMessage(roomListJson);
                broadcastCount++;
                logger.debug("Sent ROOM_LIST to " + s.username + " (roomId=-1)");
              } else {
                logger.debug("Skipped " + s.username + " because roomId=" + s.roomId);
                skippedCount++;
              }
            }
            logger.info(
                "Room list update: broadcast to "
                    + broadcastCount
                    + " clients, skipped "
                    + skippedCount
                    + " (not in lobby)");
          } catch (Exception e) {
            // 修正：将异常信息拼接到字符串中
            Logger.getInstance()
                .error("Unexpected error during room list broadcast: " + e.getMessage());
          }
        });

    GameCommandHandler commandHandler =
        new GameCommandHandler(roomManager, sessionManager, heartbeatService, leaderboardRepo);
    CommandDispatcher dispatcher = new CommandDispatcher();
    dispatcher.register("USER", commandHandler::handleUser);
    dispatcher.register("QUIT", commandHandler::handleQuit);
    dispatcher.register("JOIN", commandHandler::handleJoin);
    dispatcher.register("CREATE", commandHandler::handleCreate);
    dispatcher.register("INPUT", commandHandler::handleInput);
    dispatcher.register("ROOM_LIST", commandHandler::handleRoomList);
    dispatcher.register("LEADERBOARD", commandHandler::handleLeaderboard);
    dispatcher.register("PING", commandHandler::handlePing);
    dispatcher.register("PONG", commandHandler::handlePong);

    MessageRouter router = new DefaultMessageRouter(dispatcher);
    AdminService adminService = new DefaultAdminService(roomManager, notifier);
    QueryService queryService = new DefaultQueryService(roomManager);

    Gateway gateway =
        new Gateway(
            port,
            roomManager,
            sessionManager,
            heartbeatService,
            adminService,
            queryService,
            router);

    try {
      gateway.start();
    } catch (IOException e) {
      e.printStackTrace();
    }
    Runtime.getRuntime().addShutdownHook(new Thread(dbManager::shutdown));
  }

  private static String buildRoomListJson(RoomManager roomManager) {
    var rooms = JsonUtils.MAPPER.createArrayNode();
    for (var entry : roomManager.getRoomList()) {
      var room = JsonUtils.MAPPER.createObjectNode();
      room.put("id", entry.roomId);
      room.put("status", entry.status == RoomStatus.OPEN ? "OPEN" : "FULL");
      room.put("players", entry.playerCount);
      room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
      rooms.add(room);
    }
    var resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "ROOM_LIST");
    resp.set("rooms", rooms);
    try {
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{\"cmd\":\"ERROR\",\"message\":\"Cannot build room list\"}";
    }
  }
}
