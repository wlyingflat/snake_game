package snake.gateway;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import snake.util.ILogger;
import snake.util.Logger;

public class DefaultSessionManager implements SessionManager {
  private final ConcurrentHashMap<String, ClientSession> sessionIdMap = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ClientSession> usernameMap = new ConcurrentHashMap<>();
  private final ILogger logger = Logger.getInstance();

  @Override
  public void registerSession(ClientSession session) {
    sessionIdMap.put(session.getSessionId(), session);
    logger.debug("Session registered: " + session.getSessionId());
  }

  @Override
  public ClientSession getSession(String sessionId) {
    return sessionIdMap.get(sessionId);
  }

  @Override
  public ClientSession getSessionByUsername(String username) {
    return usernameMap.get(username);
  }

  @Override
  public void removeSession(String sessionId) {
    ClientSession session = sessionIdMap.remove(sessionId);
    if (session != null && session.username != null) {
      usernameMap.remove(session.username);
    }
    logger.debug("Session removed: " + sessionId);
  }

  @Override
  public void sendToUser(String username, String message) {
    ClientSession session = usernameMap.get(username);
    if (session != null && !session.closed) {
      session.sendMessage(message);
    }
  }

  @Override
  public void sendToUsers(Collection<String> usernames, String message) {
    for (String username : usernames) {
      sendToUser(username, message);
    }
  }

  @Override
  public Collection<ClientSession> getAllSessions() {
    return sessionIdMap.values();
  }

  @Override
  public boolean isUsernameOnline(String username) {
    return usernameMap.containsKey(username);
  }

  @Override
  public void bindUsername(String sessionId, String username) {
    ClientSession session = sessionIdMap.get(sessionId);
    if (session != null) {
      session.username = username;
      usernameMap.put(username, session);
    }
  }

  @Override
  public void unbindUsername(String username) {
    usernameMap.remove(username);
  }
}
