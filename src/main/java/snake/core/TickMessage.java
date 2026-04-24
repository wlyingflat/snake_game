package snake.core;

public record TickMessage() implements Message {
  @Override
  public String type() {
    return "TICK";
  }
}
