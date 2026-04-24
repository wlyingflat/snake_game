package snake.gateway;

import snake.common.ISession;

public class DefaultMessageRouter implements MessageRouter {
  private final CommandDispatcher dispatcher;

  public DefaultMessageRouter(CommandDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Override
  public void route(ISession session, String jsonMessage) {
    dispatcher.dispatch(session, jsonMessage);
  }
}
