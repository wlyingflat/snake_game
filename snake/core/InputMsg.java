// snake/core/InputMsg.java
package snake.core;

import snake.common.Direction;

public record InputMsg(String username, Direction direction) implements Message {
  @Override
  public String type() {
    return "INPUT";
  }
}
