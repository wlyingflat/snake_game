package snake.gateway.command;

import snake.network.ISession;

public interface MessageRouter {
  void route(ISession session, String jsonMessage);
}
