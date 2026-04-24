package snake.gateway.session;

import java.nio.channels.SocketChannel;
import snake.network.NioServer;
import snake.network.NioSession;

public class ClientSession extends NioSession {
  public String username;
  public volatile int roomId = -1;
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
  public void sendMessage(String response) {
    if (closed) return;
    super.sendMessage(response);
  }
}
