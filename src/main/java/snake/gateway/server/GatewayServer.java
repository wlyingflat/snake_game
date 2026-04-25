package snake.gateway.server;

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
import snake.gateway.handler.CommandDispatcher;
import snake.gateway.heartbeat.HeartbeatService;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;
import snake.mq.MessageBus;

public class GatewayServer {
  private static final AttributeKey<ClientSession> SESSION_KEY = AttributeKey.valueOf("session");

  private final int port;
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final CommandDispatcher dispatcher;
  private final String gatewayId;
  private final DistributedCoordinator coordinator;
  private final MessageBus messageBus;

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private final EventExecutorGroup bizGroup;
  private Channel serverChannel;
  private final ILogger logger = Logger.getInstance();

  public GatewayServer(
      int port,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      CommandDispatcher dispatcher,
      String gatewayId,
      DistributedCoordinator coordinator,
      MessageBus messageBus) {
    this.port = port;
    this.sessionManager = sessionManager;
    this.heartbeatService = heartbeatService;
    this.dispatcher = dispatcher;
    this.gatewayId = gatewayId;
    this.coordinator = coordinator;
    this.messageBus = messageBus;
    this.bizGroup = new DefaultEventExecutorGroup(16);
  }

  public void start() throws InterruptedException {
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
                pipeline.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
                pipeline.addLast(new LengthFieldPrepender(4));
                pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
                pipeline.addLast(new PingPongHandler(heartbeatService));
                pipeline.addLast(bizGroup, new GatewayHandler());
              }
            })
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

    serverChannel = bootstrap.bind(port).sync().channel();
    logger.info("GatewayServer started on port " + port);
  }

  public void stop() {
    if (serverChannel != null) serverChannel.close();
    if (bossGroup != null) bossGroup.shutdownGracefully();
    if (workerGroup != null) workerGroup.shutdownGracefully();
    if (bizGroup != null) {
      bizGroup.shutdownGracefully();
      try {
        bizGroup.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

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
        dispatcher.dispatch(session, cmd, root);
        heartbeatService.refresh(session);
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
}
