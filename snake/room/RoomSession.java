package snake.room;

import java.nio.channels.SocketChannel;
import snake.common.NioServer;
import snake.common.NioSession;

public class RoomSession extends NioSession {
  public String username;

  public RoomSession(SocketChannel channel, NioServer server) {
    super(channel, server);
  }
}
