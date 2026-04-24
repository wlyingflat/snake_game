package snake.game.event;

public record LeaveRoomMsg(String username) implements Message {
  @Override
  public String type() {
    return "LEAVE";
  }
}
