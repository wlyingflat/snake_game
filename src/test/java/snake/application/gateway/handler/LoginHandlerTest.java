package snake.application.gateway.handler;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import snake.application.gateway.heartbeat.HeartbeatService;
import snake.application.gateway.session.*;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.auth.GatewayAuthClient;

class LoginHandlerTest {
  private GatewayAuthClient authClient;
  private SessionManager sessionManager;
  private DistributedCoordinator coordinator;
  private HeartbeatService heartbeatService;
  private LoginHandler handler;
  private ClientSession session;
  private ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    authClient = mock(GatewayAuthClient.class);
    sessionManager = mock(SessionManager.class);
    coordinator = mock(DistributedCoordinator.class);
    heartbeatService = mock(HeartbeatService.class);
    when(coordinator.isOnline(anyString())).thenReturn(false);

    handler =
        new LoginHandler(
            authClient, sessionManager, coordinator, "gw-1", s -> {}, heartbeatService);

    session = mock(ClientSession.class);
    when(session.isActive()).thenReturn(true);
    when(session.getSessionId()).thenReturn("test-session-id"); // 添加此行
  }

  @Test
  void successfulLoginShouldSendLoginOk() {
    String username = "test";
    JsonNode payload = mapper.createObjectNode().put("username", username).put("password", "pass");
    GatewayAuthClient.AuthResult result = GatewayAuthClient.AuthResult.success(null, 0);
    when(authClient.login(username, "pass")).thenReturn(result);

    handler.handle(session, payload);

    verify(session).sendMessage(contains("LOGIN_OK"));
    verify(sessionManager).bindUsername(eq("test-session-id"), eq(username));
  }

  @Test
  void alreadyOnlineShouldSendError() {
    when(coordinator.isOnline("test")).thenReturn(true);
    JsonNode payload = mapper.createObjectNode().put("username", "test").put("password", "pass");
    handler.handle(session, payload);
    verify(session).sendMessage(contains("ERROR"));
  }
}
