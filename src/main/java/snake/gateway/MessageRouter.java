package snake.gateway;

import snake.common.ISession;

public interface MessageRouter {
  void route(ISession session, String jsonMessage);
}
