package snake.infrastructure.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import snake.common.ILogger;
import snake.common.JsonUtils;
import snake.common.Logger;

public class GatewayAuthClient {
  private final HttpClient httpClient;
  private final String authServiceUrl;
  private final ObjectMapper mapper = JsonUtils.MAPPER;
  private final ILogger logger = Logger.getInstance();

  public GatewayAuthClient(String authServiceUrl) {
    this.authServiceUrl = authServiceUrl;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public AuthResult register(String username, String password) {
    ObjectNode request = mapper.createObjectNode();
    request.put("username", username);
    request.put("password", password);
    return post("/auth/register", request);
  }

  public AuthResult login(String username, String password) {
    ObjectNode request = mapper.createObjectNode();
    request.put("username", username);
    request.put("password", password);
    return post("/auth/login", request);
  }

  public AuthResult logout(String username) {
    ObjectNode request = mapper.createObjectNode();
    request.put("username", username);
    return post("/auth/logout", request);
  }

  private AuthResult post(String path, ObjectNode body) {
    try {
      String json = mapper.writeValueAsString(body);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(authServiceUrl + path))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        JsonNode node = mapper.readTree(response.body());
        return new AuthResult(node);
      }
      return AuthResult.failure("Auth service error");
    } catch (IOException | InterruptedException e) {
      logger.error("Auth HTTP call failed: " + e.getMessage());
      return AuthResult.failure("Network error");
    }
  }

  public static class AuthResult {
    public final boolean success;
    public final String message;
    public final String gatewayHost;
    public final int gatewayPort;

    private AuthResult(boolean success, String message, String gatewayHost, int gatewayPort) {
      this.success = success;
      this.message = message;
      this.gatewayHost = gatewayHost;
      this.gatewayPort = gatewayPort;
    }

    private AuthResult(JsonNode json) {
      this.success = json.get("success").asBoolean();
      this.message = json.has("message") ? json.get("message").asText() : null;
      this.gatewayHost = json.has("gatewayHost") ? json.get("gatewayHost").asText() : null;
      this.gatewayPort = json.has("gatewayPort") ? json.get("gatewayPort").asInt() : 0;
    }

    public static AuthResult failure(String message) {
      return new AuthResult(false, message, null, 0);
    }

    public static AuthResult success(String gatewayHost, int gatewayPort) {
      return new AuthResult(true, null, gatewayHost, gatewayPort);
    }
  }
}
