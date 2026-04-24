package snake.gateway.command;

import snake.network.ISession;

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
