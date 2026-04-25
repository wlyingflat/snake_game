package snake.domain.game;

public record TickMessage() implements Message {
  @Override
  public String type() {
    return "TICK";
  }
}
