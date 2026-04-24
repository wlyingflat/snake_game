package snake.gateway;

import java.util.Collection;

public interface SessionManager {
  void registerSession(ClientSession session);

  ClientSession getSession(String sessionId);

  ClientSession getSessionByUsername(String username);

  void removeSession(String sessionId);

  void sendToUser(String username, String message);

  void sendToUsers(Collection<String> usernames, String message);

  Collection<ClientSession> getAllSessions();

  boolean isUsernameOnline(String username);

  void bindUsername(String sessionId, String username);

  void unbindUsername(String username);
}
