package snake.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import snake.fbs.*;

public class GatewayClient {
  private Socket socket;
  private OutputStream out;
  private InputStream in;
  private final String host;
  private final int port;
  private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
  private Thread receiverThread;
  private volatile boolean running = false;
  private volatile boolean receiverStarted = false;
  private AgarFrameListener agarFrameListener;
  private RoomListListener roomListListener;
  private DeathListener deathListener;
  private final ObjectMapper mapper = new ObjectMapper();

  // 协议常量
  private static final byte TYPE_BINARY = 0x00;
  private static final byte TYPE_TEXT = 0x01;
  private static final byte SUBTYPE_FULL_STATE = 0x00;

  public interface AgarFrameListener {
    void onFrame(AgarFrameData data);
  }

  public interface RoomListListener {
    void onRoomListUpdate(JsonNode roomListJson);
  }

  public interface DeathListener {
    void onDeath();
  }

  public GatewayClient(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public boolean connect() {
    try {
      socket = new Socket(host, port);
      out = socket.getOutputStream();
      in = socket.getInputStream();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public void sendJson(Map<String, Object> json) {
    try {
      String jsonStr = mapper.writeValueAsString(json);
      byte[] body = jsonStr.getBytes(StandardCharsets.UTF_8);
      ByteBuffer buf = ByteBuffer.allocate(4 + 1 + body.length);
      buf.putInt(1 + body.length);
      buf.put(TYPE_TEXT);
      buf.put(body);
      out.write(buf.array());
      out.flush();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void sendCommand(String cmd) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("cmd", cmd);
    sendJson(msg);
  }

  public synchronized void startMessageReceiver() {
    if (receiverStarted) return;
    receiverStarted = true;
    running = true;
    receiverThread =
        new Thread(
            () -> {
              ByteBuffer lengthBuf = ByteBuffer.allocate(4);
              int expectedLength = -1;
              ByteBuffer messageBuf = null;
              while (running) {
                try {
                  if (expectedLength == -1) {
                    int read =
                        in.read(lengthBuf.array(), lengthBuf.position(), lengthBuf.remaining());
                    if (read == -1) break;
                    lengthBuf.position(lengthBuf.position() + read);
                    if (!lengthBuf.hasRemaining()) {
                      lengthBuf.flip();
                      expectedLength = lengthBuf.getInt();
                      lengthBuf.clear();
                      if (expectedLength <= 0 || expectedLength > 1024 * 1024) {
                        throw new RuntimeException("Invalid message length: " + expectedLength);
                      }
                      messageBuf = ByteBuffer.allocate(expectedLength);
                    }
                  } else {
                    int read =
                        in.read(messageBuf.array(), messageBuf.position(), messageBuf.remaining());
                    if (read == -1) break;
                    messageBuf.position(messageBuf.position() + read);
                    if (!messageBuf.hasRemaining()) {
                      messageBuf.flip();
                      byte[] frame = new byte[messageBuf.remaining()];
                      messageBuf.get(frame);
                      processFrame(frame);
                      expectedLength = -1;
                      messageBuf = null;
                    }
                  }
                } catch (IOException e) {
                  break;
                } catch (Exception e) {
                  e.printStackTrace();
                  break;
                }
              }
              running = false;
              receiverStarted = false;
            });
    receiverThread.start();
  }

  private void processFrame(byte[] frame) {
    if (frame.length < 1) return;
    byte type = frame[0];
    if (type == TYPE_TEXT) {
      String json = new String(frame, 1, frame.length - 1, StandardCharsets.UTF_8);
      handleJsonMessage(json);
    } else if (type == TYPE_BINARY) {
      if (frame.length < 2) return;
      byte subType = frame[1];
      int dataLen = frame.length - 2;
      if (dataLen < 4) return;
      try {
        ByteBuffer dataBuf = ByteBuffer.wrap(frame, 2, dataLen);
        dataBuf.order(ByteOrder.LITTLE_ENDIAN);
        if (subType == SUBTYPE_FULL_STATE) {
          AgarFrame agar = AgarFrame.getRootAsAgarFrame(dataBuf);
          applyAgarFrame(agar);
        } // 忽略其他子类型
      } catch (Exception e) {
        System.err.println("[ERROR] Failed to parse FlatBuffers frame: " + e.getMessage());
      }
    }
  }

  private void applyAgarFrame(AgarFrame agar) {
    AgarFrameData data = new AgarFrameData();
    data.balls = new ArrayList<>();
    for (int i = 0; i < agar.ballsLength(); i++) {
      BallState fbBall = agar.balls(i);
      AgarBall ball = new AgarBall();
      ball.username = fbBall.username();
      ball.x = fbBall.x();
      ball.y = fbBall.y();
      ball.mass = fbBall.mass();
      data.balls.add(ball);
    }
    data.foods = new ArrayList<>();
    for (int i = 0; i < agar.foodLength(); i++) {
      Vec2 f = agar.food(i);
      data.foods.add(new float[] {f.x(), f.y()});
    }
    if (agarFrameListener != null) {
      agarFrameListener.onFrame(data);
    }
  }

  private void handleJsonMessage(String json) {
    System.out.println("[GATEWAY] text: " + json);
    try {
      JsonNode root = mapper.readTree(json);
      String cmd = root.get("cmd").asText();
      switch (cmd) {
        case "ROOM_LIST":
          if (roomListListener != null) roomListListener.onRoomListUpdate(root);
          break;
        case "JOIN_OK":
        case "JOIN_FAIL":
        case "ERROR":
        case "REGISTER_OK":
        case "LOGIN_OK":
          messageQueue.offer(json);
          break;
        case "YOU_DIED":
          if (deathListener != null) deathListener.onDeath();
          messageQueue.offer(json);
          break;
        case "LEADERBOARD":
          messageQueue.offer(json);
          break;
        case "PING":
          sendCommand("PONG");
          break;
        default:
          messageQueue.offer(json);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public String pollMessage() {
    try {
      return messageQueue.poll(100, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      return null;
    }
  }

  public void stopMessageReceiver() {
    running = false;
    receiverStarted = false;
    if (receiverThread != null) receiverThread.interrupt();
  }

  public void setAgarFrameListener(AgarFrameListener listener) {
    this.agarFrameListener = listener;
  }

  public void setRoomListListener(RoomListListener listener) {
    this.roomListListener = listener;
  }

  public void setDeathListener(DeathListener listener) {
    this.deathListener = listener;
  }

  public void close() {
    running = false;
    receiverStarted = false;
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
  }

  // ---- 新的游戏命令发送 ----
  public void sendMove(float x, float y) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("cmd", "MOVE");
    msg.put("x", x);
    msg.put("y", y);
    sendJson(msg);
  }

  public void sendSplit(float x, float y) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("cmd", "SPLIT");
    msg.put("x", x);
    msg.put("y", y);
    sendJson(msg);
  }

  public void sendEject(float x, float y) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("cmd", "EJECT");
    msg.put("x", x);
    msg.put("y", y);
    sendJson(msg);
  }
}
