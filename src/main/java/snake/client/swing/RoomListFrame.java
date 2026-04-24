// snake/client/swing/RoomListFrame.java
package snake.client.swing;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import snake.client.GatewayClient;
import snake.client.MainServerClient;

public class RoomListFrame extends JFrame {
  private final GameApp app;
  private JList<String> roomList;
  private DefaultListModel<String> listModel;
  private JButton refreshBtn, createBtn, joinBtn, logoutBtn;
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

    JPanel buttonPanel = new JPanel(new GridLayout(1, 4, 10, 0));
    refreshBtn = new JButton("Refresh");
    createBtn = new JButton("Create Room");
    joinBtn = new JButton("Join Selected");
    logoutBtn = new JButton("Logout");
    buttonPanel.add(refreshBtn);
    buttonPanel.add(createBtn);
    buttonPanel.add(joinBtn);
    buttonPanel.add(logoutBtn);
    add(buttonPanel, BorderLayout.SOUTH);

    GatewayClient gateway = app.getGatewayClient();
    gateway.setRoomListListener(this::onRoomListUpdate);
    gateway.sendCommand("ROOM_LIST");

    refreshBtn.addActionListener(e -> gateway.sendCommand("ROOM_LIST"));
    createBtn.addActionListener(e -> createRoom());
    joinBtn.addActionListener(e -> joinSelectedRoom());
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
              // 只显示房间ID、状态、玩家数
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

  // snake/client/swing/RoomListFrame.java
  private boolean waitForJoinOk() {
    GatewayClient gateway = app.getGatewayClient();
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < 5000) {
      String msg = gateway.pollMessage();
      if (msg != null) {
        // 接受 CREATE_OK 或 JOIN_OK
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
    try (MainServerClient main = new MainServerClient(app.getServerHost(), app.getServerPort())) {
      main.logout(app.getUsername());
    } catch (IOException e) {
      // ignore
    }
    GatewayClient gateway = app.getGatewayClient();
    gateway.sendCommand("QUIT");
    gateway.stopMessageReceiver();
    gateway.close();
    dispose();
    new LoginFrame(app).setVisible(true);
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
