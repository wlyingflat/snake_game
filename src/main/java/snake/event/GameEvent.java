package snake.event;

public interface GameEvent {
  String getEventType();

  String toJson();
}
