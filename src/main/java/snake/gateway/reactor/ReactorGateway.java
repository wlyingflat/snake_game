package snake.gateway.reactor;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
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
import snake.network.IServer;

public class ReactorGateway implements IServer {
  private static final AttributeKey<ClientSession> SESSION_KEY = AttributeKey.valueOf("session");

  private final int port;
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final MessageDispatcher dispatcher;
  private final GatewayAuthClient authClient;
  private final DistributedCoordinator coordinator;
  private final String gatewayId;
  private final EventExecutorGroup bizGroup;
  private final MessageBus messageBus;

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private Channel serverChannel;

  private final ILogger logger = Logger.getInstance();

  public ReactorGateway(
      int port,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      MessageDispatcher dispatcher,
      GatewayAuthClient authClient,
      DistributedCoordinator coordinator,
      MessageBus messageBus,
      String gatewayId) {
    this.port = port;
    this.sessionManager = sessionManager;
    this.heartbeatService = heartbeatService;
    this.dispatcher = dispatcher;
    this.authClient = authClient;
    this.coordinator = coordinator;
    this.messageBus = messageBus;
    this.gatewayId = gatewayId;

    this.bizGroup = new DefaultEventExecutorGroup(16);
  }

  public String getGatewayId() {
    return gatewayId;
  }

  @Override
  public void start() throws Exception {
    if (coordinator != null) coordinator.registerGateway(gatewayId);
    heartbeatService.start();

    if (Epoll.isAvailable()) {
      bossGroup = new EpollEventLoopGroup(1);
      workerGroup = new EpollEventLoopGroup();
    } else {
      bossGroup = new NioEventLoopGroup(1);
      workerGroup = new NioEventLoopGroup();
    }

    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap
        .group(bossGroup, workerGroup)
        .channel(
            Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ChannelPipeline pipeline = ch.pipeline();
                // 编解码器
                pipeline.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
                pipeline.addLast(new LengthFieldPrepender(4));
                pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
                // 快速路径：PING/PONG 在 I/O 线程
                pipeline.addLast(new PingPongHandler());
                // 业务处理器：在 bizGroup 中执行
                pipeline.addLast(bizGroup, new GatewayHandler());
              }
            })
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

    serverChannel = bootstrap.bind(port).sync().channel();
    logger.info("ReactorGateway " + gatewayId + " started on port " + port);
  }

  @Override
  public void stop() {
    if (serverChannel != null) {
      serverChannel.close();
    }
    if (bossGroup != null) {
      bossGroup.shutdownGracefully();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully();
    }
    heartbeatService.stop();
    if (bizGroup != null) {
      bizGroup.shutdownGracefully();
      try {
        bizGroup.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (coordinator != null) coordinator.unregisterGateway(gatewayId);
  }

  // ---------- 快速路径 Handler：PING/PONG ----------
  private class PingPongHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      String jsonMsg = (String) msg;
      JsonNode root;
      try {
        root = JsonUtils.MAPPER.readTree(jsonMsg);
      } catch (Exception e) {
        logger.error("PingPong parse error: " + e.getMessage());
        return;
      }
      String cmd = root.get("cmd").asText();

      if ("PING".equals(cmd)) {
        ClientSession session = ctx.channel().attr(SESSION_KEY).get();
        ctx.writeAndFlush("{\"cmd\":\"PONG\"}");
        if (session != null) {
          heartbeatService.refresh(session);
        }
        return;
      }
      if ("PONG".equals(cmd)) {
        ClientSession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
          session.pendingPong = false;
          session.lastHeartbeat = System.currentTimeMillis() / 1000;
          heartbeatService.refresh(session);
        }
        return;
      }
      // 其他消息交给下一个 Handler
      ctx.fireChannelRead(msg);
    }
  }

  // ---------- 业务 Handler：运行在 bizGroup ----------
  private class GatewayHandler extends ChannelInboundHandlerAdapter {
    private ClientSession session;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      session = new ClientSession(ctx.channel());
      ctx.channel().attr(SESSION_KEY).set(session);
      sessionManager.registerSession(session);
      heartbeatService.refresh(session);
      logger.debug("New connection: " + session.getSessionId());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      String jsonMsg = (String) msg;
      try {
        JsonNode root = JsonUtils.MAPPER.readTree(jsonMsg);
        String cmd = root.get("cmd").asText();

        switch (cmd) {
          case "REGISTER":
            handleRegister(session, root);
            break;
          case "LOGIN":
          case "USER":
            handleLogin(session, root);
            break;
          case "LOGOUT":
            handleLogout(session, root);
            break;
          case "ROOM_LIST":
            handleRoomList(session);
            break;
          case "LEADERBOARD":
            handleLeaderboard(session, root);
            break;
          case "CREATE":
          case "JOIN":
          case "INPUT":
          case "LEAVE":
          case "QUIT":
            handleGameCommand(session, root);
            break;
          default:
            session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Unknown command\"}");
        }
      } catch (Exception e) {
        logger.error("Error processing message: " + e.getMessage());
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      session.close();
      heartbeatService.remove(session);
      if (session.username != null) {
        if (session.roomId != -1 && coordinator != null && messageBus != null) {
          String workerId = coordinator.getRoomWorker(session.roomId);
          if (workerId != null) {
            EnhancedMessage leaveMsg =
                EnhancedMessage.newInstance()
                    .init("LEAVE", session.username, session.roomId, gatewayId, "{}");
            try {
              messageBus.sendToWorker(workerId, leaveMsg.toJson());
            } finally {
              leaveMsg.recycle();
            }
          }
        }
        sessionManager.unbindUsername(session.username);
        if (coordinator != null) {
          coordinator.markOffline(session.username);
          coordinator.removePlayerLocation(session.username);
        }
      }
      sessionManager.removeSession(session.getSessionId());
      ctx.channel().attr(SESSION_KEY).set(null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      logger.error("Exception in gateway connection: " + cause.getMessage());
      ctx.close();
    }
  }

  // ======================== 业务方法（保持不变） ========================

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
        if (s != null && s.isActive() && s.username != null && s.roomId == -1) {
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
}
