package snake.auth;

import java.nio.channels.SocketChannel;
import snake.network.NioServer;
import snake.network.NioSession;

public class MainSession extends NioSession {
  public MainSession(SocketChannel channel, NioServer server) {
    super(channel, server);
  }
}
