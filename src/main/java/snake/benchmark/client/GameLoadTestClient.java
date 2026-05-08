package snake.benchmark.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import snake.application.gateway.server.ProtocolFrameDecoder;
import snake.application.gateway.server.ProtocolFrameEncoder;

public class GameLoadTestClient {
  private final String host;
  private final int port;
  private final int concurrentClients;
  private final int durationSeconds;
  private final AtomicLong successCount = new AtomicLong(0);
  private final AtomicLong totalTx = new AtomicLong(0);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
  private final EventLoopGroup group;

  // 保存所有已建立的连接，用于测试结束后关闭
  private final List<Channel> channels = new CopyOnWriteArrayList<>();

  public GameLoadTestClient(String host, int port, int concurrentClients, int durationSeconds) {
    this.host = host;
    this.port = port;
    this.concurrentClients = concurrentClients;
    this.durationSeconds = durationSeconds;
    this.group = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
  }

  public void start() throws InterruptedException {
    System.out.println(
        "Starting load test with "
            + concurrentClients
            + " clients for "
            + durationSeconds
            + "s...");

    CountDownLatch latch = new CountDownLatch(concurrentClients);
    for (int i = 0; i < concurrentClients; i++) {
      final int clientId = i;
      new Thread(
              () -> {
                try {
                  createClient(clientId);
                } catch (Exception e) {
                  System.err.println("Client " + clientId + " creation failed: " + e.getMessage());
                } finally {
                  latch.countDown();
                }
              },
              "client-init-" + i)
          .start();
      Thread.sleep(10); // 控制连接速率
    }

    boolean allConnected = latch.await(30, TimeUnit.SECONDS);
    if (!allConnected) {
      System.err.println(
          "Warning: Not all clients connected in time, proceeding with "
              + channels.size()
              + " clients");
    }
    System.out.println("All clients connected (" + channels.size() + "), running test...");

    // 定时输出 TPS
    ScheduledFuture<?> statsFuture =
        scheduler.scheduleAtFixedRate(
            () -> {
              long tx = totalTx.getAndSet(0);
              System.out.printf(
                  "[%s] TPS: %d | Success: %d%n",
                  java.time.LocalTime.now(), tx, successCount.get());
            },
            1,
            1,
            TimeUnit.SECONDS);

    // 运行指定时间
    Thread.sleep(durationSeconds * 1000L);

    // 停止统计
    statsFuture.cancel(false);

    // 自动登出：通知所有客户端下线（关闭前发送 LOGOUT 命令）
    System.out.println("Sending LOGOUT to all clients...");
    for (Channel ch : channels) {
      if (ch.isActive()) {
        ch.writeAndFlush("{\"cmd\":\"LOGOUT\"}").awaitUninterruptibly(200);
      }
    }
    // 给网络一点时间发送完消息
    Thread.sleep(500);

    // 关闭所有连接
    System.out.println("Test duration reached, closing all connections...");
    for (Channel ch : channels) {
      if (ch.isOpen()) {
        ch.close().awaitUninterruptibly(500);
      }
    }

    // 关闭 Netty 事件循环组
    group.shutdownGracefully().sync();
    scheduler.shutdown();
    try {
      scheduler.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }

    System.out.println("Test completed.");
    System.exit(0);
  }

  private void createClient(int id) {
    Bootstrap b = new Bootstrap();
    b.group(group)
        .channel(NioSocketChannel.class)
        .handler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ChannelPipeline p = ch.pipeline();
                p.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                p.addLast(new ProtocolFrameDecoder());
                p.addLast(new LengthFieldPrepender(4));
                p.addLast(new ProtocolFrameEncoder());
                p.addLast(new LoadTestClientHandler(id, successCount, totalTx, durationSeconds));
              }
            });

    ChannelFuture f = b.connect(host, port).awaitUninterruptibly();
    if (f.isSuccess()) {
      channels.add(f.channel());
    } else {
      System.err.println("Client " + id + " failed to connect: " + f.cause());
    }
  }

  public static void main(String[] args) throws Exception {
    String host = args.length > 0 ? args[0] : "127.0.0.1";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    int clients = args.length > 2 ? Integer.parseInt(args[2]) : 500;
    int duration = args.length > 3 ? Integer.parseInt(args[3]) : 60;

    GameLoadTestClient test = new GameLoadTestClient(host, port, clients, duration);
    test.start();
  }
}
