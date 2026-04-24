package snake.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.*;
import snake.util.ILogger;
import snake.util.Logger;

public class MainServer extends NioServer {
  private IAuthenticationService authService;
  private IGatewayProcessManager gatewayManager;
  private final ConcurrentHashMap<SocketChannel, MainSession> sessions = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();
  private final ILogger logger = Logger.getInstance();

  public MainServer(int port, int gatewayPort) {
    super(port);
    this.authService = new UserManager("users.txt");
    this.gatewayManager = new DefaultGatewayProcessManager();
    gatewayManager.startGateway(gatewayPort);
  }

  @Override
  protected String getServerName() {
    return "MainServer";
  }

  @Override
  protected ISession createSession(SocketChannel channel) {
    MainSession session = new MainSession(channel, this);
    sessions.put(channel, session);
    return session;
  }

  @Override
  protected void processMessage(ISession session, String jsonMsg) {
    MainSession mainSession = (MainSession) session;
    try {
      ObjectNode root = (ObjectNode) mapper.readTree(jsonMsg);
      String cmd = root.get("cmd").asText();

      switch (cmd) {
        case "REGISTER":
          String username = root.get("username").asText();
          String password = root.get("password").asText();
          if (authService.register(username, password)) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "REGISTER_OK");
            mainSession.sendMessage(resp.toString());
          } else {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "ERROR");
            resp.put("message", "Registration failed");
            mainSession.sendMessage(resp.toString());
          }
          break;
        case "LOGIN":
          String loginUser = root.get("username").asText();
          String loginPass = root.get("password").asText();
          if (authService.login(loginUser, loginPass)) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "LOGIN_OK");
            resp.put("gatewayHost", "127.0.0.1");
            resp.put("gatewayPort", Config.GATEWAY_DEFAULT_PORT);
            mainSession.sendMessage(resp.toString());
          } else {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "ERROR");
            resp.put("message", "Login failed");
            mainSession.sendMessage(resp.toString());
          }
          break;
        case "LOGOUT":
          String logoutUser = root.get("username").asText();
          authService.logout(logoutUser);
          ObjectNode resp = mapper.createObjectNode();
          resp.put("cmd", "LOGOUT_OK");
          mainSession.sendMessage(resp.toString());
          break;
        default:
          ObjectNode error = mapper.createObjectNode();
          error.put("cmd", "ERROR");
          error.put("message", "Unknown command");
          mainSession.sendMessage(error.toString());
      }
    } catch (Exception e) {
      logger.error("Error processing message: " + e.getMessage());
      try {
        ObjectNode error = mapper.createObjectNode();
        error.put("cmd", "ERROR");
        error.put("message", "Internal error");
        mainSession.sendMessage(error.toString());
      } catch (Exception ignored) {
      }
      closeSession(mainSession);
    }
  }

  @Override
  protected void onSessionClosed(ISession session) {
    sessions.remove(((MainSession) session).channel);
  }

  @Override
  protected void cleanup() {
    running = false;
    if (authService instanceof UserManager) ((UserManager) authService).save();
    gatewayManager.stopGateway();
    super.cleanup();
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: MainServer <port> [gatewayPort]");
      System.exit(1);
    }
    int port = Integer.parseInt(args[0]);
    int gatewayPort = args.length >= 2 ? Integer.parseInt(args[1]) : Config.GATEWAY_DEFAULT_PORT;
    try {
      new MainServer(port, gatewayPort).start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
