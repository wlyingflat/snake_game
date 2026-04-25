package snake.application.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import snake.application.gateway.MessageDispatcher;
import snake.application.gateway.session.ClientSession;

public class GameCommandHandler implements CommandHandler {
  private final MessageDispatcher dispatcher;

  public GameCommandHandler(MessageDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Override
  public void handle(ClientSession session, JsonNode payload) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    dispatcher.routeToWorker(session.username, payload);
  }
}
