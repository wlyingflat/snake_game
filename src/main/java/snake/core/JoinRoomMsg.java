// snake/core/JoinRoomMsg.java
package snake.core;

public record JoinRoomMsg(String username) implements Message {
  @Override
  public String type() {
    return "JOIN";
  }
}
