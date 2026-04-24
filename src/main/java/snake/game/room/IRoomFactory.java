package snake.game.room;

import snake.game.notification.IGameClientNotifier;

public interface IRoomFactory {
  Room createRoom(int roomId, IGameClientNotifier notifier, IRoomDestroyCallback callback);
}
