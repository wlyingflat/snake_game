package snake.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import snake.gateway.auth.GatewayAuthClient;
import snake.gateway.session.ClientSession;

public class RegisterHandler implements CommandHandler {
  private final GatewayAuthClient authClient;

  public RegisterHandler(GatewayAuthClient authClient) {
    this.authClient = authClient;
  }

  @Override
  public void handle(ClientSession session, JsonNode payload) {
    String username = payload.get("username").asText();
    String password = payload.get("password").asText();
    GatewayAuthClient.AuthResult result = authClient.register(username, password);
    if (result.success) {
      session.sendMessage("{\"cmd\":\"REGISTER_OK\"}");
    } else {
      session.sendMessage(
          "{\"cmd\":\"ERROR\",\"message\":\""
              + (result.message != null ? result.message : "Registration failed")
              + "\"}");
    }
  }
}
