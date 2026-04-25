package snake.infrastructure.event;

public interface GameEvent {
  String getEventType();

  String toJson();
}
