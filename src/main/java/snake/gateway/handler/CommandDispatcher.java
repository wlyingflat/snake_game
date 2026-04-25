package snake.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import snake.gateway.session.ClientSession;

public class CommandDispatcher {
  private final Map<String, CommandHandler> handlers = new HashMap<>();

  public void register(String cmd, CommandHandler handler) {
    handlers.put(cmd.toUpperCase(), handler);
  }

  public void dispatch(ClientSession session, String cmd, JsonNode payload) {
    CommandHandler handler = handlers.get(cmd.toUpperCase());
    if (handler != null) {
      handler.handle(session, payload);
    } else {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Unknown command\"}");
    }
  }
}
