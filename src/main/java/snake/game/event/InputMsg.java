package snake.game.event;

import snake.base.Direction;

public record InputMsg(String username, Direction direction) implements Message {
  @Override
  public String type() {
    return "INPUT";
  }
}
