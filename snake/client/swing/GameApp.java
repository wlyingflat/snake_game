package snake.client.swing;

import java.awt.*;
import javax.swing.*;
import snake.client.*;

public class GameApp {
  private final String serverHost;
  private final int serverPort;
  private GatewayClient gatewayClient;
  private String username;

  public GameApp(String host, int port) {
    this.serverHost = host;
    this.serverPort = port;
  }

  public String getServerHost() {
    return serverHost;
  }

  public int getServerPort() {
    return serverPort;
  }

  public void start() {
    SwingUtilities.invokeLater(() -> new LoginFrame(this).setVisible(true));
  }

  public void onLoginSuccess(String username, String gatewayHost, int gatewayPort) {
    this.username = username;
    gatewayClient = new GatewayClient(gatewayHost, gatewayPort);
    if (gatewayClient.connect()) {
      // 发送 JSON 格式的 USER 命令
      java.util.Map<String, Object> userMsg = new java.util.HashMap<>();
      userMsg.put("cmd", "USER");
      userMsg.put("username", username);
      gatewayClient.sendJson(userMsg);
      gatewayClient.startMessageReceiver();
      showRoomList();
    } else {
      JOptionPane.showMessageDialog(null, "Failed to connect to gateway");
    }
  }

  public void showRoomList() {
    SwingUtilities.invokeLater(() -> new RoomListFrame(this).setVisible(true));
  }

  public void showGame() {
    SwingUtilities.invokeLater(() -> new GameFrame(this).setVisible(true));
  }

  public GatewayClient getGatewayClient() {
    return gatewayClient;
  }

  public String getUsername() {
    return username;
  }

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("Usage: java GameApp <server_ip> <server_port>");
      System.exit(1);
    }
    new GameApp(args[0], Integer.parseInt(args[1])).start();
  }
}
