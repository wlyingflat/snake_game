import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram; // 需要添加依赖：org.hdrhistogram:HdrHistogram:2.1.12
import snake.common.Direction;

public class LoadTestClient {
  private static final String HOST = "127.0.0.1";
  private static final int PORT = 19000;
  private static final int TOTAL_PLAYERS = 500;
  private static final int ROOMS = 250;

  // 全局统计
  private static final AtomicLong msgCounter = new AtomicLong();
  private static final Histogram latencyHistogram = new Histogram(3, 1000000000L, 3);
  private static final ConcurrentHashMap<Long, Long> pendingRequests = new ConcurrentHashMap<>();
  private static final AtomicLong seqGenerator = new AtomicLong();

  public static void main(String[] args) throws Exception {
    for (int i = 0; i < TOTAL_PLAYERS; i++) {
      final int id = i;
      new Thread(
              () -> {
                try {
                  SocketChannel ch = SocketChannel.open();
                  ch.connect(new InetSocketAddress(HOST, PORT));
                  ch.configureBlocking(false);
                  // 启动读线程
                  new Thread(() -> readLoop(ch)).start();

                  // 发送 USER 命令
                  send(ch, "{\"cmd\":\"USER\",\"username\":\"player" + id + "\"}");
                  int roomId = id % ROOMS;
                  send(ch, "{\"cmd\":\"JOIN\",\"roomId\":" + roomId + "}");
                  Random rand = new Random();
                  Direction[] dirs = Direction.values();
                  while (true) {
                    Direction d = dirs[rand.nextInt(4)];
                    long seq = seqGenerator.incrementAndGet();
                    // 发送带序号的消息（服务器会原样返回？需要修改服务器，或直接利用 STATE 广播）
                    // 这里简单发送 INPUT，但延迟通过接收 STATE 测量（见读线程）
                    send(ch, "{\"cmd\":\"INPUT\",\"direction\":\"" + d + "\",\"seq\":" + seq + "}");
                    pendingRequests.put(seq, System.nanoTime());
                    msgCounter.incrementAndGet();
                    Thread.sleep(10);
                  }
                } catch (Exception e) {
                  e.printStackTrace();
                }
              })
          .start();
    }

    // 定期输出统计
    while (true) {
      Thread.sleep(5000);
      long count = msgCounter.getAndSet(0);
      double tps = count / 5.0;
      synchronized (latencyHistogram) {
        System.out.printf(
            "TPS: %.0f, P50: %.2f ms, P95: %.2f ms, P99: %.2f ms, P999: %.2f ms%n",
            tps,
            latencyHistogram.getValueAtPercentile(50) / 1_000_000.0,
            latencyHistogram.getValueAtPercentile(95) / 1_000_000.0,
            latencyHistogram.getValueAtPercentile(99) / 1_000_000.0,
            latencyHistogram.getValueAtPercentile(99.9) / 1_000_000.0);
        latencyHistogram.reset();
      }
    }
  }

  private static void readLoop(SocketChannel ch) {
    ByteBuffer lengthBuf = ByteBuffer.allocate(4);
    ByteBuffer bodyBuf = null;
    int expectedLen = -1;
    while (true) {
      try {
        if (expectedLen == -1) {
          int read = ch.read(lengthBuf);
          if (read == -1) break;
          if (lengthBuf.hasRemaining()) continue;
          lengthBuf.flip();
          expectedLen = lengthBuf.getInt();
          lengthBuf.clear();
          bodyBuf = ByteBuffer.allocate(expectedLen);
        }
        int read = ch.read(bodyBuf);
        if (read == -1) break;
        if (bodyBuf.hasRemaining()) continue;
        bodyBuf.flip();
        byte[] data = new byte[bodyBuf.remaining()];
        bodyBuf.get(data);
        String response = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        // 解析响应，提取 seq
        try {
          // 简单匹配 "seq":数字
          java.util.regex.Matcher m =
              java.util.regex.Pattern.compile("\"seq\":(\\d+)").matcher(response);
          if (m.find()) {
            long seq = Long.parseLong(m.group(1));
            Long sendTime = pendingRequests.remove(seq);
            if (sendTime != null) {
              long rttNs = System.nanoTime() - sendTime;
              synchronized (latencyHistogram) {
                latencyHistogram.recordValue(rttNs);
              }
            }
          }
        } catch (Exception e) {
          /* 忽略解析错误 */
        }
        expectedLen = -1;
        bodyBuf = null;
      } catch (Exception e) {
        break;
      }
    }
    try {
      ch.close();
    } catch (Exception ignored) {
    }
  }

  static void send(SocketChannel ch, String msg) throws Exception {
    byte[] data = msg.getBytes();
    ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
    buf.putInt(data.length);
    buf.put(data);
    buf.flip();
    while (buf.hasRemaining()) ch.write(buf);
  }
}
