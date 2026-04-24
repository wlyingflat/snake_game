package snake.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.ISession;
import snake.util.ILogger;
import snake.util.Logger;

public class CommandDispatcher {
  private final Map<String, CommandHandler> handlers = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();
  private final ILogger logger = Logger.getInstance();

  public interface CommandHandler {
    void handle(ClientSession session, JsonNode params) throws Exception;
  }

  public void register(String cmd, CommandHandler handler) {
    handlers.put(cmd, handler);
  }

  public void dispatch(ISession session, String jsonMsg) {
    ClientSession clientSession = (ClientSession) session;
    try {
      JsonNode root = mapper.readTree(jsonMsg);
      String cmd = root.get("cmd").asText();
      CommandHandler handler = handlers.get(cmd);
      if (handler != null) {
        handler.handle(clientSession, root);
      } else {
        logger.warn("Unknown command: " + cmd);
        clientSession.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Unknown command\"}");
      }
    } catch (Exception e) {
      logger.error("Command dispatch error: " + e.getMessage());
      clientSession.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Internal error\"}");
    }
  }
}
