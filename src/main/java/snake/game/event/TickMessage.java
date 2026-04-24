package snake.game.event;

public record TickMessage() implements Message {
  @Override
  public String type() {
    return "TICK";
  }
}
