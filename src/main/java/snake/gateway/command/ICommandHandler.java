package snake.gateway.command;

import com.fasterxml.jackson.databind.JsonNode;
import snake.network.ISession;

public interface ICommandHandler {
  void handle(ISession session, JsonNode params) throws Exception;
}
