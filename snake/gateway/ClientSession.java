package snake.gateway;

import java.nio.channels.SocketChannel;
import snake.common.NioServer;
import snake.common.NioSession;

public class ClientSession extends NioSession {
  public String username;
  public long lastHeartbeat; // 最后一次收到任何消息的时间（秒）
  public long lastPingSent; // 最后一次主动发送PING的时间戳（秒）
  public boolean pendingPong; // 是否已发送PING但尚未收到PONG

  public ClientSession(SocketChannel channel, NioServer server) {
    super(channel, server);
    this.lastHeartbeat = System.currentTimeMillis() / 1000;
    this.lastPingSent = 0;
    this.pendingPong = false;
  }
}
