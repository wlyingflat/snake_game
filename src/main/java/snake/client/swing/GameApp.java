package snake.client.swing;

import javax.swing.*;
import snake.client.GatewayClient;

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

  public void setGatewayClient(GatewayClient client) {
    this.gatewayClient = client;
  }

  public void onLoginSuccess(String username) {
    this.username = username;
    showRoomList();
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
      System.err.println("Usage: java GameApp <gateway_ip> <gateway_port>");
      System.exit(1);
    }
    new GameApp(args[0], Integer.parseInt(args[1])).start();
  }
}
