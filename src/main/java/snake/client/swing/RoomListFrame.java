// RoomListFrame.java - 美化版
package snake.client.swing;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import snake.client.GatewayClient;

public class RoomListFrame extends JFrame {
  private final GameApp app;
  private JList<String> roomList;
  private DefaultListModel<String> listModel;
  private JButton refreshBtn, createBtn, joinBtn, logoutBtn, leaderboardBtn;
  private List<RoomEntry> rooms = new ArrayList<>();

  public RoomListFrame(GameApp app) {
    this.app = app;
    initUI();
    loadRoomList();
    setupListeners();
  }

  private void initUI() {
    setTitle("Snake Game - Lobby");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(600, 500);
    setLocationRelativeTo(null);
    setMinimumSize(new Dimension(500, 400));

    // 主面板渐变背景
    JPanel mainPanel =
        new JPanel(new BorderLayout()) {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            GradientPaint gp =
                new GradientPaint(
                    0, 0, new Color(35, 35, 55), 0, getHeight(), new Color(20, 20, 35));
            g2d.setPaint(gp);
            g2d.fillRect(0, 0, getWidth(), getHeight());
          }
        };
    mainPanel.setOpaque(false);
    setContentPane(mainPanel);

    // 标题
    JLabel titleLabel = new JLabel("ROOM LIST", SwingConstants.CENTER);
    titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
    titleLabel.setForeground(new Color(220, 220, 100));
    titleLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 15, 0));
    mainPanel.add(titleLabel, BorderLayout.NORTH);

    // 房间列表面板
    listModel = new DefaultListModel<>();
    roomList = new JList<>(listModel);
    roomList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    roomList.setBackground(new Color(45, 45, 65));
    roomList.setForeground(Color.WHITE);
    roomList.setSelectionBackground(new Color(100, 150, 200));
    roomList.setFixedCellHeight(40);
    roomList.setCellRenderer(new RoomListRenderer());

    JScrollPane scrollPane = new JScrollPane(roomList);
    scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
    scrollPane.getViewport().setBackground(new Color(45, 45, 65));
    scrollPane.setOpaque(false);
    scrollPane.getViewport().setOpaque(false);
    // 自定义滚动条
    scrollPane
        .getVerticalScrollBar()
        .setUI(
            new BasicScrollBarUI() {
              @Override
              protected void configureScrollBarColors() {
                this.thumbColor = new Color(100, 100, 140);
                this.trackColor = new Color(40, 40, 60);
              }
            });
    mainPanel.add(scrollPane, BorderLayout.CENTER);

    // 按钮面板
    JPanel buttonPanel = new JPanel(new GridLayout(1, 5, 15, 0));
    buttonPanel.setOpaque(false);
    buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 20, 20));

    refreshBtn = createStyledButton("Refresh", new Color(70, 130, 200));
    createBtn = createStyledButton("Create Room", new Color(100, 180, 100));
    joinBtn = createStyledButton("Join", new Color(220, 160, 60));
    leaderboardBtn = createStyledButton("Leaderboard", new Color(160, 100, 200));
    logoutBtn = createStyledButton("Logout", new Color(200, 80, 80));

    buttonPanel.add(refreshBtn);
    buttonPanel.add(createBtn);
    buttonPanel.add(joinBtn);
    buttonPanel.add(leaderboardBtn);
    buttonPanel.add(logoutBtn);
    mainPanel.add(buttonPanel, BorderLayout.SOUTH);
  }

  private JButton createStyledButton(String text, Color bgColor) {
    JButton btn = new JButton(text);
    btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
    btn.setForeground(Color.WHITE);
    btn.setBackground(bgColor);
    btn.setFocusPainted(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bgColor.darker(), 1),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));
    btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    btn.setOpaque(true);
    btn.addMouseListener(
        new MouseAdapter() {
          public void mouseEntered(MouseEvent e) {
            btn.setBackground(bgColor.brighter());
          }

          public void mouseExited(MouseEvent e) {
            btn.setBackground(bgColor);
          }
        });
    return btn;
  }

  private void setupListeners() {
    refreshBtn.addActionListener(e -> loadRoomList());
    createBtn.addActionListener(e -> createRoom());
    joinBtn.addActionListener(e -> joinSelectedRoom());
    leaderboardBtn.addActionListener(e -> showLeaderboard());
    logoutBtn.addActionListener(e -> logout());
  }

  private void loadRoomList() {
    GatewayClient gateway = app.getGatewayClient();
    gateway.setRoomListListener(this::onRoomListUpdate);
    gateway.sendCommand("ROOM_LIST");
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
              String line = String.format("%-3d  %-7s  %2d/%-4d", id, status, players, maxPlayers);
              rooms.add(new RoomEntry(id, line));
            }
          }
          updateListDisplay();
        });
  }

  private void updateListDisplay() {
    listModel.clear();
    if (rooms.isEmpty()) {
      listModel.addElement("  No active rooms.");
    } else {
      for (RoomEntry entry : rooms) {
        listModel.addElement(entry.line);
      }
    }
  }

  private void createRoom() {
    String input =
        JOptionPane.showInputDialog(
            this, "Enter room ID (0-7):", "Create Room", JOptionPane.PLAIN_MESSAGE);
    if (input == null) return;
    try {
      int roomId = Integer.parseInt(input.trim());
      if (roomId < 0 || roomId >= 8) throw new NumberFormatException();
      GatewayClient gateway = app.getGatewayClient();
      gateway.sendJson(Map.of("cmd", "CREATE", "roomId", roomId));
      if (waitForJoinOk()) {
        dispose();
        app.showGame();
      } else {
        JOptionPane.showMessageDialog(
            this, "Create room failed", "Error", JOptionPane.ERROR_MESSAGE);
      }
    } catch (NumberFormatException e) {
      JOptionPane.showMessageDialog(
          this, "Room ID must be between 0 and 7", "Invalid Input", JOptionPane.WARNING_MESSAGE);
    }
  }

  private void joinSelectedRoom() {
    int idx = roomList.getSelectedIndex();
    if (idx < 0 || idx >= rooms.size()) {
      JOptionPane.showMessageDialog(
          this, "Please select a room", "No Selection", JOptionPane.WARNING_MESSAGE);
      return;
    }
    int roomId = rooms.get(idx).id;
    GatewayClient gateway = app.getGatewayClient();
    gateway.sendJson(Map.of("cmd", "JOIN", "roomId", roomId));
    if (waitForJoinOk()) {
      dispose();
      app.showGame();
    } else {
      JOptionPane.showMessageDialog(this, "Join room failed", "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private boolean waitForJoinOk() {
    GatewayClient gateway = app.getGatewayClient();
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < 5000) {
      String msg = gateway.pollMessage();
      if (msg != null) {
        if (msg.contains("\"cmd\":\"CREATE_OK\"") || msg.contains("\"cmd\":\"JOIN_OK\""))
          return true;
        if (msg.contains("\"cmd\":\"JOIN_FAIL\"") || msg.contains("\"cmd\":\"ERROR\""))
          return false;
        if (msg.contains("\"cmd\":\"PING\"")) gateway.sendCommand("PONG");
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
    new LeaderboardDialog(this, gateway).setVisible(true);
  }

  static class RoomEntry {
    int id;
    String line;

    RoomEntry(int id, String line) {
      this.id = id;
      this.line = line;
    }
  }

  class RoomListRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      JLabel label =
          (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      label.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(70, 70, 90)),
              BorderFactory.createEmptyBorder(8, 15, 8, 15)));
      label.setFont(new Font("Monospaced", Font.PLAIN, 14));
      if (!isSelected) {
        label.setBackground(new Color(45, 45, 65));
        label.setForeground(Color.WHITE);
      } else {
        label.setBackground(new Color(80, 130, 180));
      }
      return label;
    }
  }
}
