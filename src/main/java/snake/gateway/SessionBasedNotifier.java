package snake.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import snake.core.IGameClientNotifier;
import snake.util.ILogger;
import snake.util.Logger;

public class SessionBasedNotifier implements IGameClientNotifier {
  private final SessionManager sessionManager;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ILogger logger = Logger.getInstance();

  public SessionBasedNotifier(SessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  @Override
  public void notifyPlayer(String username, String message) {
    ClientSession session = sessionManager.getSessionByUsername(username);
    if (session != null && !session.closed) {
      try {
        var root = mapper.readTree(message);
        String cmd = root.get("cmd").asText();
        if ("JOIN_OK".equals(cmd)) {
          int roomId = root.get("roomId").asInt();
          session.roomId = roomId;
        } else if ("YOU_DIED".equals(cmd)) {
          session.roomId = -1;
        } else if ("JOIN_FAIL".equals(cmd)) {
          session.roomId = -1;
        }
      } catch (Exception e) {
        // ignore
      }
      session.sendMessage(message);
    }
  }

  @Override
  public void notifyPlayers(Collection<String> usernames, String message) {
    for (String u : usernames) notifyPlayer(u, message);
  }
}
