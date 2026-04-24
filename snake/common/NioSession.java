package snake.common;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * NIO 会话基类，封装每个客户端连接的基本资源： - SocketChannel - 读缓冲区（ByteBuffer） - 半包拼接缓冲区（StringBuilder） -
 * 写队列（Queue<String>） 提供 enqueueResponse 方法自动触发写就绪注册。
 */
public class NioSession {
  public final SocketChannel channel;
  public final ByteBuffer readBuffer;
  public final StringBuilder pendingMessage;
  public final Queue<String> writeQueue;
  protected NioServer server; // 用于触发写事件注册

  public NioSession(SocketChannel channel, NioServer server) {
    this.channel = channel;
    this.readBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
    this.pendingMessage = new StringBuilder();
    this.writeQueue = new ConcurrentLinkedQueue<>();
    this.server = server;
  }

  /** 将响应消息加入写队列，并通知服务器注册 OP_WRITE 事件。 */
  public void enqueueResponse(String response) {
    writeQueue.add(response);
    if (server != null) {
      server.scheduleWrite(this);
    }
  }
}
