package snake.gateway;

import java.nio.channels.SocketChannel;
import snake.common.NioServer;
import snake.common.NioSession;

public class ClientSession extends NioSession {
  public String username;
  public volatile int roomId = -1;

  // 心跳相关字段 —— 添加 volatile 保证多线程可见性
  public volatile long lastHeartbeat;
  public volatile long lastPingSent;
  public volatile boolean pendingPong;

  public volatile boolean closed = false;

  public ClientSession(SocketChannel channel, NioServer server) {
    super(channel, server);
    this.lastHeartbeat = System.currentTimeMillis() / 1000;
    this.lastPingSent = 0;
    this.pendingPong = false;
  }

  @Override
  public void enqueueResponse(String response) {
    if (closed) {
      return;
    }
    super.enqueueResponse(response);
  }
}
