package snake.core;

import java.util.Collection;

public interface IGameClientNotifier {
  void notifyPlayer(String username, String message);

  void notifyPlayers(Collection<String> usernames, String message);
}
