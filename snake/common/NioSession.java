package snake.common;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NioSession {
  public final SocketChannel channel;
  public final ByteBuffer readBuffer; // 临时读缓冲区
  public final Queue<String> writeQueue;
  public final ByteBuffer writeBuffer;
  protected NioServer server;

  // 长度前缀读取状态机
  private int expectedLength = -1; // -1 表示正在读长度
  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
  private ByteBuffer messageBuffer = null;

  public NioSession(SocketChannel channel, NioServer server) {
    this.channel = channel;
    this.readBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
    this.writeQueue = new ConcurrentLinkedQueue<>();
    this.server = server;
    this.writeBuffer = ByteBuffer.allocateDirect(Config.BUFFER_SIZE);
    this.lengthBuffer.clear();
    this.expectedLength = -1;
  }

  public void enqueueResponse(String response) {
    writeQueue.add(response);
    if (server != null) {
      server.scheduleWrite(this);
    }
  }

  /**
   * 将新读取的数据解析为完整的消息（JSON 字符串）。
   *
   * @param newData 本次读取的数据（未翻转，position 在已读位置）
   * @return 解析出的完整消息列表
   * @throws RuntimeException 当消息长度非法时抛出
   */
  public List<String> parseReadData(ByteBuffer newData) throws RuntimeException {
    List<String> messages = new ArrayList<>();
    while (newData.hasRemaining()) {
      if (expectedLength == -1) {
        // 读取长度前缀
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
        // 读取消息体
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
          // 重置状态
          expectedLength = -1;
          messageBuffer = null;
        }
      }
    }
    return messages;
  }
}
