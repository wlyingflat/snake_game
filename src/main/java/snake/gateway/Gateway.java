package snake.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.common.Config;
import snake.common.ISession;
import snake.common.NioServer;
import snake.common.RoomStatus;
import snake.core.IGameClientNotifier;
import snake.core.LeaveRoomMsg;
import snake.core.Room;
import snake.core.RoomManager;

/** 重构后的 Gateway - 只负责 NIO 网络收发 + 消息路由。 所有其他职责已委托给独立组件。 */
public class Gateway extends NioServer {
  private final MessageRouter messageRouter;
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final AdminService adminService;
  private final QueryService queryService;
  private final RoomManager roomManager;
  private final IGameClientNotifier notifier;
  private final ExecutorService workerPool;

  public Gateway(
      int port,
      RoomManager roomManager,
      IGameClientNotifier notifier,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      AdminService adminService,
      QueryService queryService,
      MessageRouter messageRouter) {
    super(port);
    this.roomManager = roomManager;
    this.notifier = notifier;
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
    heartbeatService.refresh(client); // 取消心跳超时任务（实际实现中 refresh 会重置，但此处应取消）
    if (client.username != null) {
      sessionManager.unbindUsername(client.username);
      // 通知领域层用户断开
      if (roomManager instanceof RoomManager) {
        // 可选：通过 MessageDispatcher 通知 RoomManager 清理
      }
    }
    if (client.roomId != -1) {
      Room room = roomManager.getRoom(client.roomId);
      if (room != null) room.post(new LeaveRoomMsg(client.username));
    }
    sessionManager.removeSession(session.getSessionId());
  }

  @Override
  public void start() throws IOException {
    // 启动辅助服务
    adminService.start();
    queryService.start();
    heartbeatService.start();
    // 启动网络服务
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

    // 依赖组装（也可使用 DI 框架）
    SessionManager sessionManager = new DefaultSessionManager();
    HeartbeatService heartbeatService =
        new DefaultHeartbeatService(
            session -> {
              // 超时回调：关闭会话
              session.close();
            });
    RoomManager roomManager = new RoomManager(null, null); // 暂不传递 notifier 和回调，稍后设置
    IGameClientNotifier notifier = new SessionBasedNotifier(sessionManager);
    // 重新设置 RoomManager 的 notifier 和回调（需要修改 RoomManager 添加 setter）
    roomManager.setNotifier(notifier);
    roomManager.setRoomListUpdateCallback(
        () -> {
          // 广播房间列表更新到所有未加入房间的客户端
          String roomListJson = buildRoomListJson(roomManager);
          for (ClientSession s : sessionManager.getAllSessions()) {
            if (s.username != null && s.roomId == -1) {
              s.sendMessage(roomListJson);
            }
          }
        });

    GameCommandHandler commandHandler =
        new GameCommandHandler(roomManager, sessionManager, heartbeatService);
    CommandDispatcher dispatcher = new CommandDispatcher();
    dispatcher.register("USER", commandHandler::handleUser);
    dispatcher.register("QUIT", commandHandler::handleQuit);
    dispatcher.register("JOIN", commandHandler::handleJoin);
    dispatcher.register("CREATE", commandHandler::handleCreate);
    dispatcher.register("INPUT", commandHandler::handleInput);
    dispatcher.register("ROOM_LIST", commandHandler::handleRoomList);
    dispatcher.register("PING", commandHandler::handlePing);
    dispatcher.register("PONG", commandHandler::handlePong);

    MessageRouter router = new DefaultMessageRouter(dispatcher);
    AdminService adminService = new DefaultAdminService(roomManager, notifier);
    QueryService queryService = new DefaultQueryService(roomManager);

    Gateway gateway =
        new Gateway(
            port,
            roomManager,
            notifier,
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
  }

  private static String buildRoomListJson(RoomManager roomManager) {
    // 复用 GameCommandHandler 中的逻辑，此处简化，实际可抽取工具方法
    ObjectMapper mapper = new ObjectMapper();
    var rooms = mapper.createArrayNode();
    for (var entry : roomManager.getRoomList()) {
      var room = mapper.createObjectNode();
      room.put("id", entry.roomId);
      room.put("status", entry.status == RoomStatus.OPEN ? "OPEN" : "FULL");
      room.put("players", entry.playerCount);
      room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
      rooms.add(room);
    }
    var resp = mapper.createObjectNode();
    resp.put("cmd", "ROOM_LIST");
    resp.set("rooms", rooms);
    try {
      return mapper.writeValueAsString(resp);
    } catch (Exception e) {
      return "{\"cmd\":\"ERROR\",\"message\":\"Cannot build room list\"}";
    }
  }
}
