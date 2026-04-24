package snake.core;

public interface IRoomFactory {
  Room createRoom(int roomId, IGameClientNotifier notifier, IRoomDestroyCallback callback);
}
