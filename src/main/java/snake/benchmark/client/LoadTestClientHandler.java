package snake.benchmark.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import snake.common.JsonUtils;

public class LoadTestClientHandler extends ChannelInboundHandlerAdapter {
  private static final boolean DEBUG = true; // 打开调试，观察前几个客户端
  private static final int MAP_WIDTH = 3000;
  private static final int MAP_HEIGHT = 3000;

  private final int clientId;
  private final AtomicLong successCount;
  private final AtomicLong totalTx;
  private final int durationSeconds;
  private final ScheduledExecutorService tickScheduler;
  private ScheduledFuture<?> tickTask;
  private String username;
  private int roomId;
  private volatile boolean loggedIn = false;
  private volatile boolean loginCounted = false;
  private volatile boolean joined = false;

  public LoadTestClientHandler(
      int clientId, AtomicLong successCount, AtomicLong totalTx, int durationSeconds) {
    this.clientId = clientId;
    this.successCount = successCount;
    this.totalTx = totalTx;
    this.durationSeconds = durationSeconds;
    this.username = "loaduser" + clientId;
    this.roomId = 1 + (clientId % 80);
    this.tickScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "tick-client-" + clientId);
              t.setDaemon(true);
              return t;
            });
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    if (clientId < 5) System.out.println("Client " + clientId + " connected");
    // 直接发送 LOGIN，不做 REGISTER
    ObjectNode login = JsonUtils.MAPPER.createObjectNode();
    login.put("cmd", "LOGIN");
    login.put("username", username);
    login.put("password", "password");
    ctx.writeAndFlush(login.toString());
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof String text) {
      totalTx.incrementAndGet();
      if (DEBUG && clientId < 2) {
        System.out.println("[DEBUG client " + clientId + "] recv: " + text);
      }
      try {
        var root = JsonUtils.MAPPER.readTree(text);
        String cmd = root.get("cmd").asText();
        switch (cmd) {
          case "LOGIN_OK":
            if (!loginCounted) {
              loginCounted = true;
              successCount.incrementAndGet();
            }
            loggedIn = true;
            createOrJoinRoom(ctx);
            break;
          case "JOIN_OK":
            if (!joined) {
              joined = true;
              successCount.incrementAndGet();
            }
            startSendingActions(ctx);
            break;
          case "PONG":
            break;
          case "ERROR":
            String errMsg = root.has("message") ? root.get("message").asText() : "";
            if (clientId < 2) {
              System.err.println("Client " + clientId + " error: " + errMsg);
            }
            // 如果是因为在线冲突，重试登录
            if (errMsg.contains("already online")) {
              ctx.executor()
                  .schedule(
                      () -> {
                        if (ctx.channel().isActive()) {
                          ObjectNode relogin = JsonUtils.MAPPER.createObjectNode();
                          relogin.put("cmd", "LOGIN");
                          relogin.put("username", username);
                          relogin.put("password", "password");
                          ctx.writeAndFlush(relogin.toString());
                        }
                      },
                      3,
                      TimeUnit.SECONDS);
            } else if (errMsg.contains("Room not available")
                || errMsg.contains("Cannot create room")
                || errMsg.contains("Room not found")) {
              ctx.executor()
                  .schedule(
                      () -> {
                        if (ctx.channel().isActive()) createOrJoinRoom(ctx);
                      },
                      2,
                      TimeUnit.SECONDS);
            }
            break;
          default:
            break;
        }
      } catch (Exception e) {
        // ignore
      }
    } else if (msg instanceof ByteBuf buf) {
      totalTx.incrementAndGet();
      buf.release();
    } else {
      if (msg instanceof ByteBuf unknownBuf) unknownBuf.release();
    }
  }

  private void createOrJoinRoom(ChannelHandlerContext ctx) {
    ObjectNode create = JsonUtils.MAPPER.createObjectNode();
    create.put("cmd", "CREATE");
    create.put("roomId", roomId);
    ctx.writeAndFlush(create.toString());
  }

  private void startSendingActions(ChannelHandlerContext ctx) {
    if (tickTask != null) return;
    tickTask =
        tickScheduler.scheduleAtFixedRate(
            () -> {
              if (!loggedIn) return;
              float targetX = (float) (Math.random() * MAP_WIDTH);
              float targetY = (float) (Math.random() * MAP_HEIGHT);
              ObjectNode move = JsonUtils.MAPPER.createObjectNode();
              move.put("cmd", "MOVE");
              move.put("x", targetX);
              move.put("y", targetY);
              ctx.writeAndFlush(move.toString());
              if (Math.random() < 0.1) {
                ObjectNode split = JsonUtils.MAPPER.createObjectNode();
                split.put("cmd", "SPLIT");
                split.put("x", targetX);
                split.put("y", targetY);
                ctx.writeAndFlush(split.toString());
              }
              if (Math.random() < 0.05) {
                ObjectNode eject = JsonUtils.MAPPER.createObjectNode();
                eject.put("cmd", "EJECT");
                eject.put("x", targetX);
                eject.put("y", targetY);
                ctx.writeAndFlush(eject.toString());
              }
            },
            0,
            200,
            TimeUnit.MILLISECONDS);

    tickScheduler.schedule(
        () -> {
          ctx.close();
          tickScheduler.shutdown();
        },
        durationSeconds,
        TimeUnit.SECONDS);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (tickTask != null) tickTask.cancel(false);
    tickScheduler.shutdown();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    ctx.close();
  }
}
