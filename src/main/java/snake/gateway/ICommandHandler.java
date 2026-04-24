package snake.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import snake.common.ISession;

public interface ICommandHandler {
  void handle(ISession session, JsonNode params) throws Exception;
}
