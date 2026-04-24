package snake.network;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;

public class NioSession implements ISession {
  public final SocketChannel channel;
  public final ByteBuffer readBuffer;
  public final Queue<String> writeQueue;
  public final ByteBuffer writeBuffer;
  protected NioServer server;

  private ByteBuffer pendingBuffer = null;
  private int expectedLength = -1;
  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
  private ByteBuffer messageBuffer = null;
  private final String sessionId;

  private static final ILogger logger = Logger.getInstance();

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
    if (Config.DEBUG_MESSAGE_LOGGING) {
      logger.debug("[SEND] session=" + sessionId + ", msg=" + message);
    }
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
          if (Config.DEBUG_MESSAGE_LOGGING) {
            logger.debug("[RECV] session=" + sessionId + ", msg=" + json);
          }
          messages.add(json);
          expectedLength = -1;
          messageBuffer = null;
        }
      }
    }
    return messages;
  }
}
