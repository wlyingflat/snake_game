package snake.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.*;
import snake.util.Logger;

public class MainServer extends NioServer {
  private UserManager userManager;
  private final Map<SocketChannel, MainSession> sessions = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();

  public MainServer(int port, int gatewayPort) {
    super(port);
    this.userManager = new UserManager("users.txt");
    startGatewayProcess(gatewayPort);
  }

  @Override
  protected String getServerName() {
    return "MainServer";
  }

  @Override
  protected NioSession createSession(SocketChannel channel) {
    MainSession session = new MainSession(channel, this);
    sessions.put(channel, session);
    return session;
  }

  @Override
  protected void processMessage(NioSession session, String jsonMsg) {
    MainSession mainSession = (MainSession) session;
    try {
      ObjectNode root = (ObjectNode) mapper.readTree(jsonMsg);
      String cmd = root.get("cmd").asText();

      switch (cmd) {
        case "REGISTER":
          String username = root.get("username").asText();
          String password = root.get("password").asText();
          if (userManager.register(username, password)) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "REGISTER_OK");
            mainSession.enqueueResponse(resp.toString());
          } else {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "ERROR");
            resp.put("message", "Registration failed");
            mainSession.enqueueResponse(resp.toString());
          }
          break;
        case "LOGIN":
          String loginUser = root.get("username").asText();
          String loginPass = root.get("password").asText();
          if (userManager.login(loginUser, loginPass)) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "LOGIN_OK");
            resp.put("gatewayHost", "127.0.0.1");
            resp.put("gatewayPort", Config.GATEWAY_DEFAULT_PORT);
            mainSession.enqueueResponse(resp.toString());
          } else {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "ERROR");
            resp.put("message", "Login failed");
            mainSession.enqueueResponse(resp.toString());
          }
          break;
        case "LOGOUT":
          String logoutUser = root.get("username").asText();
          userManager.logout(logoutUser);
          ObjectNode resp = mapper.createObjectNode();
          resp.put("cmd", "LOGOUT_OK");
          mainSession.enqueueResponse(resp.toString());
          break;
        default:
          ObjectNode error = mapper.createObjectNode();
          error.put("cmd", "ERROR");
          error.put("message", "Unknown command");
          mainSession.enqueueResponse(error.toString());
      }
    } catch (Exception e) {
      Logger.error("Error processing message: " + e.getMessage());
      try {
        ObjectNode error = mapper.createObjectNode();
        error.put("cmd", "ERROR");
        error.put("message", "Internal error");
        mainSession.enqueueResponse(error.toString());
      } catch (Exception ignored) {
      }
      closeSession(mainSession);
    }
  }

  @Override
  protected void onSessionClosed(NioSession session) {
    sessions.remove(session.channel);
  }

  private void startGatewayProcess(int gatewayPort) {
    try {
      String projectDir = System.getProperty("user.dir");
      ProcessBuilder pb =
          new ProcessBuilder(
              "mvn",
              "exec:java",
              "-Dexec.mainClass=snake.gateway.Gateway",
              "-Dexec.args=" + Config.GATEWAY_DEFAULT_PORT);
      pb.directory(new File(projectDir));
      pb.inheritIO();
      pb.start();
      Logger.info("Gateway process started on port " + gatewayPort);
      Thread.sleep(500);
    } catch (IOException | InterruptedException e) {
      Logger.error("Failed to start gateway: " + e.getMessage());
    }
  }

  @Override
  public void start() throws IOException {
    super.start();
  }

  @Override
  protected void cleanup() {
    running = false;
    userManager.save();
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
