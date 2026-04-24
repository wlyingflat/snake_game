// snake/core/LeaveRoomMsg.java
package snake.core;

public record LeaveRoomMsg(String username) implements Message {
  @Override
  public String type() {
    return "LEAVE";
  }
}
