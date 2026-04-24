package snake.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import snake.common.GameStateData;
import snake.common.Serializer;

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
  private GameStateListener gameStateListener;
  private RoomListListener roomListListener;
  private DeathListener deathListener;
  private final ObjectMapper mapper = new ObjectMapper();

  public interface GameStateListener {
    void onGameState(String json, GameStateData data);
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
      ByteBuffer buf = ByteBuffer.allocate(4 + body.length);
      buf.putInt(body.length);
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

  // 旧接口，向后兼容（逐步废弃）
  public void send(String message) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("cmd", message);
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
                      byte[] body = new byte[messageBuf.remaining()];
                      messageBuf.get(body);
                      String json = new String(body, StandardCharsets.UTF_8);
                      handleMessage(json);
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

  public void stopMessageReceiver() {
    running = false;
    receiverStarted = false;
    if (receiverThread != null) {
      receiverThread.interrupt();
    }
  }

  private void handleMessage(String json) {
    try {
      JsonNode root = mapper.readTree(json);
      String cmd = root.get("cmd").asText();

      switch (cmd) {
        case "ROOM_LIST":
          if (roomListListener != null) {
            roomListListener.onRoomListUpdate(root);
          }
          break;
        case "JOIN_OK":
        case "JOIN_FAIL":
        case "ERROR":
          messageQueue.offer(json);
          break;
        case "STATE":
          GameStateData data = Serializer.deserializeGameState(json);
          if (gameStateListener != null) {
            gameStateListener.onGameState(json, data);
          }
          break;
        case "YOU_DIED":
          if (deathListener != null) {
            deathListener.onDeath();
          }
          messageQueue.offer(json);
          break;
        case "PING":
          sendCommand("PONG");
          break;
        case "PONG":
          // ignore
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

  public void setGameStateListener(GameStateListener listener) {
    this.gameStateListener = listener;
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
      if (out != null) out.close();
      if (socket != null) socket.close();
    } catch (IOException e) {
    }
  }
}
