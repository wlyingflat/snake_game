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
  private static final boolean DEBUG = false;

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
    System.out.println("Client " + clientId + " connected");
    ObjectNode register = JsonUtils.MAPPER.createObjectNode();
    register.put("cmd", "REGISTER");
    register.put("username", username);
    register.put("password", "password");
    ctx.writeAndFlush(register.toString());

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
      if (DEBUG && clientId < 5) {
        System.out.println("[DEBUG client " + clientId + "] recv: " + text);
      }
      try {
        var root = JsonUtils.MAPPER.readTree(text);
        String cmd = root.get("cmd").asText();
        switch (cmd) {
          case "REGISTER_OK":
            break;
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
            startSendingInputs(ctx);
            break;
          case "PONG":
            break;
          case "ERROR":
            String errMsg = root.has("message") ? root.get("message").asText() : "";
            if (errMsg.contains("Room not available")
                || errMsg.contains("Cannot create room")
                || errMsg.contains("Room not found")) {
              ctx.executor()
                  .schedule(
                      () -> {
                        if (ctx.channel().isActive()) {
                          createOrJoinRoom(ctx);
                        }
                      },
                      2,
                      TimeUnit.SECONDS);
            } else {
              System.err.println("Client " + clientId + " fatal error: " + text);
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
      if (msg instanceof ByteBuf unknownBuf) {
        unknownBuf.release();
      }
    }
  }

  private void createOrJoinRoom(ChannelHandlerContext ctx) {
    ObjectNode create = JsonUtils.MAPPER.createObjectNode();
    create.put("cmd", "CREATE");
    create.put("roomId", roomId);
    ctx.writeAndFlush(create.toString());
  }

  private void startSendingInputs(ChannelHandlerContext ctx) {
    if (tickTask != null) return;
    tickTask =
        tickScheduler.scheduleAtFixedRate(
            () -> {
              if (!loggedIn) return;
              ObjectNode input = JsonUtils.MAPPER.createObjectNode();
              input.put("cmd", "INPUT");
              input.put("direction", randomDirection());
              ctx.writeAndFlush(input.toString());
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

  private String randomDirection() {
    String[] dirs = {"UP", "DOWN", "LEFT", "RIGHT"};
    return dirs[(int) (Math.random() * 4)];
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (tickTask != null) tickTask.cancel(false);
    tickScheduler.shutdown();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }
}
