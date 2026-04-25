package snake.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;
import snake.gateway.auth.GatewayAuthClient;
import snake.gateway.heartbeat.HeartbeatService;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;

public class LoginHandler implements CommandHandler {
  private final GatewayAuthClient authClient;
  private final SessionManager sessionManager;
  private final DistributedCoordinator coordinator;
  private final String gatewayId;
  private final Consumer<ClientSession> sendRoomList;
  private final HeartbeatService heartbeatService;
  private final ILogger logger = Logger.getInstance();

  public LoginHandler(
      GatewayAuthClient authClient,
      SessionManager sessionManager,
      DistributedCoordinator coordinator,
      String gatewayId,
      Consumer<ClientSession> sendRoomList,
      HeartbeatService heartbeatService) {
    this.authClient = authClient;
    this.sessionManager = sessionManager;
    this.coordinator = coordinator;
    this.gatewayId = gatewayId;
    this.sendRoomList = sendRoomList;
    this.heartbeatService = heartbeatService;
  }

  @Override
  public void handle(ClientSession session, JsonNode payload) {
    String username = payload.get("username").asText();
    String password = payload.get("password").asText();

    if (coordinator != null && coordinator.isOnline(username)) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"User already online\"}");
      return;
    }

    GatewayAuthClient.AuthResult result = authClient.login(username, password);
    if (result.success) {
      session.username = username;
      sessionManager.bindUsername(session.getSessionId(), username);
      if (coordinator != null) {
        coordinator.markOnline(username);
        coordinator.setPlayerLocation(username, gatewayId, -1);
      }
      String loginOk =
          String.format(
              "{\"cmd\":\"LOGIN_OK\",\"gatewayHost\":\"%s\",\"gatewayPort\":%d}",
              Config.GATEWAY_HOST, Config.GATEWAY_PORT);
      session.sendMessage(loginOk);
      sendRoomList.accept(session);
      heartbeatService.refresh(session);
      logger.info("User logged in: " + username);
    } else {
      session.sendMessage(
          "{\"cmd\":\"ERROR\",\"message\":\""
              + (result.message != null ? result.message : "Login failed")
              + "\"}");
    }
  }
}
