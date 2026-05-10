package snake.client.tester;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import snake.fbs.*;

public class ProtocolTester {
  private static final byte TYPE_TEXT = 0x01;
  private static final ObjectMapper mapper = new ObjectMapper();

  private final String host;
  private final int port;
  private final String username;
  private final String password;
  private Socket socket;
  private OutputStream out;
  private InputStream in;
  private volatile boolean running = true;

  public ProtocolTester(String host, int port, String username, String password) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
  }

  public static void main(String[] args) {
    if (args.length < 4) {
      System.out.println("Usage: java ProtocolTester <host> <port> <username> <password>");
      System.exit(1);
    }
    String host = args[0];
    int port = Integer.parseInt(args[1]);
    String user = args[2];
    String pass = args[3];

    ProtocolTester tester = new ProtocolTester(host, port, user, pass);
    try {
      // 先连接，再启动接收线程
      tester.connect();
      System.out.println("[CONNECTED] " + host + ":" + port);
      new Thread(tester::receiveLoop, "receiver").start();
    } catch (IOException e) {
      System.err.println("Failed to connect: " + e.getMessage());
      System.exit(1);
    }

    try {
      Thread.sleep(500);

      // 1. 登录
      tester.sendJson(Map.of("cmd", "LOGIN", "username", user, "password", pass));
      Thread.sleep(1000);

      // 2. 请求房间列表（两次）
      tester.sendCommand("ROOM_LIST");
      Thread.sleep(500);
      tester.sendCommand("ROOM_LIST");
      Thread.sleep(500);

      // 3. 尝试创建房间
      tester.sendJson(Map.of("cmd", "CREATE", "roomId", 1));
      Thread.sleep(1000);

      // 4. 再请求一次房间列表
      tester.sendCommand("ROOM_LIST");
      Thread.sleep(500);

      // 5. 发送 MOVE 命令
      for (int i = 0; i < 5; i++) {
        tester.sendJson(Map.of("cmd", "MOVE", "x", 500, "y", 500));
        Thread.sleep(100);
      }

      // 6. 测试 SPLIT / EJECT
      tester.sendJson(Map.of("cmd", "SPLIT", "x", 600, "y", 600));
      Thread.sleep(200);
      tester.sendJson(Map.of("cmd", "EJECT", "x", 700, "y", 700));
      Thread.sleep(200);

      // 保持一段时间接收二进制帧
      System.out.println("--------------- 等待 5 秒接收游戏状态 ---------------");
      Thread.sleep(5000);

      // 7. 最后登出
      tester.sendCommand("LOGOUT");
      Thread.sleep(500); // 确保登出消息发出并被接收

    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      tester.running = false;
      tester.close();
    }
  }

  void connect() throws IOException {
    socket = new Socket(host, port);
    out = socket.getOutputStream();
    in = socket.getInputStream(); // 创建 input stream
  }

  void sendCommand(String cmd) {
    sendJson(Map.of("cmd", cmd));
  }

  void sendJson(Map<String, Object> json) {
    try {
      String jsonStr = mapper.writeValueAsString(json);
      byte[] body = jsonStr.getBytes(StandardCharsets.UTF_8);
      ByteBuffer buf = ByteBuffer.allocate(4 + 1 + body.length);
      buf.putInt(1 + body.length);
      buf.put(TYPE_TEXT);
      buf.put(body);
      out.write(buf.array());
      out.flush();
      System.out.println("[SEND] " + jsonStr);
    } catch (IOException e) {
      System.err.println("[ERROR] send: " + e.getMessage());
    }
  }

  void receiveLoop() {
    try {
      ByteBuffer lengthBuf = ByteBuffer.allocate(4);
      int expected = -1;
      ByteBuffer msgBuf = null;
      while (running) {
        if (expected == -1) {
          int r = in.read(lengthBuf.array(), lengthBuf.position(), lengthBuf.remaining());
          if (r == -1) break;
          lengthBuf.position(lengthBuf.position() + r);
          if (!lengthBuf.hasRemaining()) {
            lengthBuf.flip();
            expected = lengthBuf.getInt();
            lengthBuf.clear();
            if (expected <= 0 || expected > 2 * 1024 * 1024) {
              System.err.println("Invalid length " + expected);
              break;
            }
            msgBuf = ByteBuffer.allocate(expected);
          }
        } else {
          int r = in.read(msgBuf.array(), msgBuf.position(), msgBuf.remaining());
          if (r == -1) break;
          msgBuf.position(msgBuf.position() + r);
          if (!msgBuf.hasRemaining()) {
            msgBuf.flip();
            byte[] frame = new byte[msgBuf.remaining()];
            msgBuf.get(frame);
            processFrame(frame);
            expected = -1;
            msgBuf = null;
          }
        }
      }
    } catch (IOException e) {
      if (running) System.err.println("Receiver error: " + e.getMessage());
    }
  }

  void processFrame(byte[] frame) {
    if (frame.length < 1) return;
    byte type = frame[0];
    if (type == TYPE_TEXT) {
      String json = new String(frame, 1, frame.length - 1, StandardCharsets.UTF_8);
      System.out.println("[RECV TEXT] " + json);
      // 自动回复 PONG
      try {
        JsonNode root = mapper.readTree(json);
        if ("PING".equals(root.get("cmd").asText())) {
          sendCommand("PONG");
        }
      } catch (Exception e) {
      }
    } else if (frame[0] == 0x00) {
      // 二进制帧
      if (frame.length < 3) return;
      byte subType = frame[1];
      int dataLen = frame.length - 2;
      System.out.printf("[RECV BINARY] subType=0x%02X, dataLen=%d%n", subType, dataLen);
      if (subType == 0x00 && dataLen >= 4) {
        ByteBuffer bb = ByteBuffer.wrap(frame, 2, dataLen);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        try {
          AgarFrame agar = AgarFrame.getRootAsAgarFrame(bb);
          int balls = agar.ballsLength();
          int foods = agar.foodLength();
          System.out.printf("  AgarFrame: balls=%d, foods=%d%n", balls, foods);
          for (int i = 0; i < Math.min(3, balls); i++) {
            BallState b = agar.balls(i);
            System.out.printf(
                "    Ball[%d]: %s pos=(%.1f, %.1f) mass=%.1f%n",
                i, b.username(), b.x(), b.y(), b.mass());
          }
          if (balls > 3) System.out.printf("    ... and %d more balls%n", balls - 3);
        } catch (Exception e) {
          System.err.println("Failed to parse AgarFrame: " + e.getMessage());
        }
      }
    }
  }

  void close() {
    running = false;
    try {
      if (in != null) in.close();
    } catch (IOException ignored) {
    }
    try {
      if (out != null) out.close();
    } catch (IOException ignored) {
    }
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
    }
    System.out.println("[CLOSED]");
  }
}
