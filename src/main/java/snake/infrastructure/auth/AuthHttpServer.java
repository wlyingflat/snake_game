package snake.infrastructure.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import snake.common.Config;
import snake.common.ILogger;
import snake.common.JsonUtils;
import snake.common.Logger;

public class AuthHttpServer {
  private final IAuthenticationService authService;
  private final ILogger logger = Logger.getInstance();
  private Server server;
  private final int port;

  public AuthHttpServer(int port, IAuthenticationService authService) {
    this.port = port;
    this.authService = authService;
  }

  public void start() throws Exception {
    server = new Server(port);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);

    context.addServlet(new ServletHolder(new AuthServlet()), "/auth/*");
    server.start();
    logger.info("Auth HTTP server started on port " + port);
  }

  public void stop() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  private class AuthServlet extends HttpServlet {
    private final ObjectMapper mapper = JsonUtils.MAPPER;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      String path = req.getPathInfo();
      String body = req.getReader().lines().collect(Collectors.joining());
      ObjectNode requestJson;

      try {
        requestJson = (ObjectNode) mapper.readTree(body);
      } catch (Exception e) {
        sendError(resp, "Invalid JSON");
        return;
      }

      ObjectNode responseJson = mapper.createObjectNode();

      switch (path) {
        case "/register":
          handleRegister(requestJson, responseJson);
          break;
        case "/login":
          handleLogin(requestJson, responseJson);
          break;
        case "/logout":
          handleLogout(requestJson, responseJson);
          break;
        default:
          sendError(resp, "Unknown endpoint");
          return;
      }

      resp.setContentType("application/json");
      resp.setStatus(HttpServletResponse.SC_OK);
      resp.getWriter().write(mapper.writeValueAsString(responseJson));
    }

    private void handleRegister(ObjectNode req, ObjectNode resp) {
      String username = req.get("username").asText();
      String password = req.get("password").asText();
      boolean success = authService.register(username, password);
      resp.put("success", success);
      if (!success) {
        resp.put("message", "Registration failed (username may already exist)");
      }
    }

    private void handleLogin(ObjectNode req, ObjectNode resp) {
      String username = req.get("username").asText();
      String password = req.get("password").asText();
      boolean success = authService.login(username, password);
      resp.put("success", success);
      if (success) {
        // 返回 Gateway 的连接信息（可从配置或环境变量读取）
        resp.put("gatewayHost", Config.GATEWAY_HOST);
        resp.put("gatewayPort", Config.GATEWAY_PORT);
      } else {
        resp.put("message", "Login failed (invalid credentials or already online)");
      }
    }

    private void handleLogout(ObjectNode req, ObjectNode resp) {
      String username = req.get("username").asText();
      authService.logout(username);
      resp.put("success", true);
    }

    private void sendError(HttpServletResponse resp, String message) throws IOException {
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      resp.setContentType("application/json");
      ObjectNode error = mapper.createObjectNode();
      error.put("success", false);
      error.put("message", message);
      resp.getWriter().write(mapper.writeValueAsString(error));
    }
  }
}
