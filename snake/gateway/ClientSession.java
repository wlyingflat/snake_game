// snake/gateway/ClientSession.java
package snake.gateway;

import java.nio.channels.SocketChannel;
import snake.common.NioServer;
import snake.common.NioSession;

public class ClientSession extends NioSession {
  public String username;
  public int roomId = -1; // 当前所在房间ID，-1表示未加入
  public long lastHeartbeat;
  public long lastPingSent;
  public boolean pendingPong;

  public ClientSession(SocketChannel channel, NioServer server) {
    super(channel, server);
    this.lastHeartbeat = System.currentTimeMillis() / 1000;
    this.lastPingSent = 0;
    this.pendingPong = false;
  }
}
