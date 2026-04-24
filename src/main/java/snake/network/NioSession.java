package snake.network;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import snake.base.Config;

public class NioSession implements ISession {
  public final SocketChannel channel;
  public final ByteBuffer readBuffer;
  public final Queue<String> writeQueue;
  public final ByteBuffer writeBuffer; // 保留供未来优化，当前版本未使用
  protected NioServer server;

  // 用于保存未发送完的 ByteBuffer（大消息或部分发送的消息）
  private ByteBuffer pendingBuffer = null;

  private int expectedLength = -1;
  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
  private ByteBuffer messageBuffer = null;
  private final String sessionId;

  public NioSession(SocketChannel channel, NioServer server) {
    this.channel = channel;
    this.readBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
    this.writeQueue = new ConcurrentLinkedQueue<>();
    this.server = server;
    this.writeBuffer = ByteBuffer.allocateDirect(Config.BUFFER_SIZE);
    this.sessionId = channel.toString();
  }

  @Override
  public void sendMessage(String message) {
    writeQueue.add(message);
    if (server != null) {
      server.scheduleWrite(this);
    }
  }

  @Override
  public void close() {
    if (server != null) {
      server.closeSession(this);
    }
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  public ByteBuffer getPendingBuffer() {
    return pendingBuffer;
  }

  public void setPendingBuffer(ByteBuffer buffer) {
    this.pendingBuffer = buffer;
  }

  public void clearPendingBuffer() {
    this.pendingBuffer = null;
  }

  public List<String> parseReadData(ByteBuffer newData) throws RuntimeException {
    List<String> messages = new ArrayList<>();
    while (newData.hasRemaining()) {
      if (expectedLength == -1) {
        int remainingInLen = lengthBuffer.remaining();
        int toCopy = Math.min(remainingInLen, newData.remaining());
        byte[] tmp = new byte[toCopy];
        newData.get(tmp);
        lengthBuffer.put(tmp);
        if (!lengthBuffer.hasRemaining()) {
          lengthBuffer.flip();
          expectedLength = lengthBuffer.getInt();
          lengthBuffer.clear();
          if (expectedLength <= 0 || expectedLength > 1024 * 1024) {
            throw new RuntimeException("Invalid message length: " + expectedLength);
          }
          messageBuffer = ByteBuffer.allocate(expectedLength);
        }
      } else {
        int remainingInBody = messageBuffer.remaining();
        int toCopy = Math.min(remainingInBody, newData.remaining());
        byte[] tmp = new byte[toCopy];
        newData.get(tmp);
        messageBuffer.put(tmp);
        if (!messageBuffer.hasRemaining()) {
          messageBuffer.flip();
          byte[] body = new byte[messageBuffer.remaining()];
          messageBuffer.get(body);
          String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
          messages.add(json);
          expectedLength = -1;
          messageBuffer = null;
        }
      }
    }
    return messages;
  }
}
