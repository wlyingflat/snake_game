package snake.room;

import java.nio.channels.SocketChannel;
import java.util.List;
import snake.common.*;

public class Player {
  public boolean isUsed;
  public SocketChannel channel;
  public String name;
  public List<Position> body;
  public int length;
  public Direction direction;
  public int score;
  public boolean isDead;
}
