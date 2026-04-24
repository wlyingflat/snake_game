package snake.client.swing;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import snake.client.GatewayClient;

public class RoomListFrame extends JFrame {
  private final GameApp app;
  private JList<String> roomList;
  private DefaultListModel<String> listModel;
  private JButton refreshBtn, createBtn, joinBtn, logoutBtn, leaderboardBtn;
  private List<RoomEntry> rooms = new ArrayList<>();

  public RoomListFrame(GameApp app) {
    this.app = app;
    setTitle("Snake Game - Room List");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(500, 400);
    setLocationRelativeTo(null);
    setLayout(new BorderLayout());

    listModel = new DefaultListModel<>();
    roomList = new JList<>(listModel);
    roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    add(new JScrollPane(roomList), BorderLayout.CENTER);

    JPanel buttonPanel = new JPanel(new GridLayout(1, 5, 10, 0));
    refreshBtn = new JButton("Refresh");
    createBtn = new JButton("Create Room");
    joinBtn = new JButton("Join Selected");
    leaderboardBtn = new JButton("Leaderboard");
    logoutBtn = new JButton("Logout");
    buttonPanel.add(refreshBtn);
    buttonPanel.add(createBtn);
    buttonPanel.add(joinBtn);
    buttonPanel.add(leaderboardBtn);
    buttonPanel.add(logoutBtn);
    add(buttonPanel, BorderLayout.SOUTH);

    GatewayClient gateway = app.getGatewayClient();
    gateway.setRoomListListener(this::onRoomListUpdate);
    gateway.sendCommand("ROOM_LIST");

    refreshBtn.addActionListener(e -> gateway.sendCommand("ROOM_LIST"));
    createBtn.addActionListener(e -> createRoom());
    joinBtn.addActionListener(e -> joinSelectedRoom());
    leaderboardBtn.addActionListener(e -> showLeaderboard());
    logoutBtn.addActionListener(e -> logout());

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            GatewayClient gw = app.getGatewayClient();
            if (gw != null) gw.setRoomListListener(null);
          }
        });
  }

  private void onRoomListUpdate(JsonNode root) {
    SwingUtilities.invokeLater(
        () -> {
          rooms.clear();
          JsonNode roomsNode = root.get("rooms");
          if (roomsNode != null && roomsNode.isArray()) {
            for (JsonNode r : roomsNode) {
              int id = r.get("id").asInt();
              String status = r.get("status").asText();
              int players = r.get("players").asInt();
              int maxPlayers = r.get("maxPlayers").asInt();
              String line = String.format("%-3d %-7s %2d/%-4d", id, status, players, maxPlayers);
              rooms.add(new RoomEntry(id, line));
            }
          }
          updateListDisplay();
        });
  }

  private void updateListDisplay() {
    listModel.clear();
    if (rooms.isEmpty()) {
      listModel.addElement("No active rooms.");
    } else {
      for (RoomEntry entry : rooms) {
        listModel.addElement(entry.line);
      }
    }
  }

  private void createRoom() {
    String input = JOptionPane.showInputDialog(this, "Enter room ID (0-7):");
    if (input == null) return;
    try {
      int roomId = Integer.parseInt(input.trim());
      if (roomId < 0 || roomId >= 8) {
        JOptionPane.showMessageDialog(this, "Room ID must be between 0 and 7");
        return;
      }
      GatewayClient gateway = app.getGatewayClient();
      gateway.sendJson(Map.of("cmd", "CREATE", "roomId", roomId));
      if (waitForJoinOk()) {
        dispose();
        app.showGame();
      } else {
        JOptionPane.showMessageDialog(this, "Create room failed");
      }
    } catch (NumberFormatException e) {
      JOptionPane.showMessageDialog(this, "Invalid number");
    }
  }

  private void joinSelectedRoom() {
    int idx = roomList.getSelectedIndex();
    if (idx < 0 || idx >= rooms.size()) {
      JOptionPane.showMessageDialog(this, "Please select a room");
      return;
    }
    int roomId = rooms.get(idx).id;
    GatewayClient gateway = app.getGatewayClient();
    gateway.sendJson(Map.of("cmd", "JOIN", "roomId", roomId));
    if (waitForJoinOk()) {
      dispose();
      app.showGame();
    } else {
      JOptionPane.showMessageDialog(this, "Join room failed");
    }
  }

  private boolean waitForJoinOk() {
    GatewayClient gateway = app.getGatewayClient();
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < 5000) {
      String msg = gateway.pollMessage();
      if (msg != null) {
        if (msg.contains("\"cmd\":\"CREATE_OK\"") || msg.contains("\"cmd\":\"JOIN_OK\"")) {
          return true;
        }
        if (msg.contains("\"cmd\":\"JOIN_FAIL\"") || msg.contains("\"cmd\":\"ERROR\"")) {
          return false;
        }
        if (msg.contains("\"cmd\":\"PING\"")) {
          gateway.sendCommand("PONG");
        }
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        break;
      }
    }
    return false;
  }

  private void logout() {
    GatewayClient gateway = app.getGatewayClient();
    gateway.sendCommand("LOGOUT");
    gateway.stopMessageReceiver();
    gateway.close();
    dispose();
    new LoginFrame(app).setVisible(true);
  }

  private void showLeaderboard() {
    GatewayClient gateway = app.getGatewayClient();
    if (gateway == null) return;
    LeaderboardDialog dialog = new LeaderboardDialog(this, gateway);
    dialog.setVisible(true);
  }

  static class RoomEntry {
    int id;
    String line;

    RoomEntry(int id, String line) {
      this.id = id;
      this.line = line;
    }
  }
}
