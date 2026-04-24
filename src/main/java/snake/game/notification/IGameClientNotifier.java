package snake.game.notification;

import java.util.Collection;

public interface IGameClientNotifier {
  void notifyPlayer(String username, String message);

  void notifyPlayers(Collection<String> usernames, String message);

  void onJoinResult(String username, int roomId, boolean success);

  // 新增：玩家离开房间回调
  default void onLeave(String username) {}

  default void updateHighScore(String username, int score) {}
}
