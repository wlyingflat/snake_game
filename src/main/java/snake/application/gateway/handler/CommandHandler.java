package snake.application.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import snake.application.gateway.session.ClientSession;

public interface CommandHandler {
  void handle(ClientSession session, JsonNode payload);
}
