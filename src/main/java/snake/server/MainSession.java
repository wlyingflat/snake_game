package snake.server;

import java.nio.channels.SocketChannel;
import snake.common.NioServer;
import snake.common.NioSession;

public class MainSession extends NioSession {
  public MainSession(SocketChannel channel, NioServer server) {
    super(channel, server);
  }
}
