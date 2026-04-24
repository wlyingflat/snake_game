package snake.game.event;

public record JoinRoomMsg(String username) implements Message {
  @Override
  public String type() {
    return "JOIN";
  }
}
