package snake.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import snake.distributed.DistributedCoordinator;
import snake.gateway.auth.GatewayAuthClient;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;
import snake.mq.MessageBus;

public class LogoutHandler implements CommandHandler {
  private final GatewayAuthClient authClient;
  private final SessionManager sessionManager;
  private final DistributedCoordinator coordinator;
  private final MessageBus messageBus;
  private final String gatewayId;

  public LogoutHandler(
      GatewayAuthClient authClient,
      SessionManager sessionManager,
      DistributedCoordinator coordinator,
      MessageBus messageBus,
      String gatewayId) {
    this.authClient = authClient;
    this.sessionManager = sessionManager;
    this.coordinator = coordinator;
    this.messageBus = messageBus;
    this.gatewayId = gatewayId;
  }

  @Override
  public void handle(ClientSession session, JsonNode payload) {
    if (session.username != null) {
      authClient.logout(session.username);
      if (coordinator != null) {
        coordinator.markOffline(session.username);
        coordinator.removePlayerLocation(session.username);
      }
    }
    sessionManager.unbindUsername(session.username);
    sessionManager.removeSession(session.getSessionId());
    session.close();
  }
}
