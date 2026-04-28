// LeaderboardDialog.java - 美化版
package snake.client.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import snake.client.GatewayClient;

public class LeaderboardDialog extends JDialog {
  private final GatewayClient gateway;
  private final ObjectMapper mapper = new ObjectMapper();
  private JTable table;
  private DefaultTableModel tableModel;

  public LeaderboardDialog(Frame owner, GatewayClient gateway) {
    super(owner, "Leaderboard", true);
    this.gateway = gateway;
    initUI();
    fetchLeaderboard();
  }

  private void initUI() {
    setSize(500, 400);
    setLocationRelativeTo(getOwner());
    JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.setBackground(new Color(35, 35, 55));
    setContentPane(mainPanel);

    // 标题
    JLabel title = new JLabel("TOP PLAYERS", SwingConstants.CENTER);
    title.setFont(new Font("Segoe UI", Font.BOLD, 20));
    title.setForeground(new Color(220, 220, 120));
    title.setBorder(BorderFactory.createEmptyBorder(15, 0, 10, 0));
    mainPanel.add(title, BorderLayout.NORTH);

    String[] columns = {"Rank", "Username", "Score"};
    tableModel =
        new DefaultTableModel(columns, 0) {
          @Override
          public boolean isCellEditable(int row, int col) {
            return false;
          }
        };
    table = new JTable(tableModel);
    table.setRowHeight(30);
    table.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    table.setBackground(new Color(45, 45, 65));
    table.setForeground(Color.WHITE);
    table.setGridColor(new Color(80, 80, 100));
    table.setSelectionBackground(new Color(80, 130, 180));
    table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 14));
    table.getTableHeader().setBackground(new Color(55, 55, 75));
    table.getTableHeader().setForeground(new Color(220, 220, 200));
    table.getTableHeader().setReorderingAllowed(false);
    // 居中显示
    DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
    centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
    table.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
    table.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);

    JScrollPane scroll = new JScrollPane(table);
    scroll.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
    scroll.getViewport().setBackground(new Color(45, 45, 65));
    mainPanel.add(scroll, BorderLayout.CENTER);

    JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    btnPanel.setOpaque(false);
    btnPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 15, 15));
    JButton refreshBtn = createStyledButton("Refresh", new Color(70, 130, 200));
    JButton closeBtn = createStyledButton("Close", new Color(180, 80, 80));
    refreshBtn.addActionListener(e -> fetchLeaderboard());
    closeBtn.addActionListener(e -> dispose());
    btnPanel.add(refreshBtn);
    btnPanel.add(closeBtn);
    mainPanel.add(btnPanel, BorderLayout.SOUTH);
  }

  private JButton createStyledButton(String text, Color bgColor) {
    JButton btn = new JButton(text);
    btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
    btn.setForeground(Color.WHITE);
    btn.setBackground(bgColor);
    btn.setFocusPainted(false);
    btn.setBorder(BorderFactory.createEmptyBorder(6, 15, 6, 15));
    btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    btn.setOpaque(true);
    return btn;
  }

  private void fetchLeaderboard() {
    tableModel.setRowCount(0);
    tableModel.addRow(new Object[] {"Loading...", "", ""});
    new Thread(
            () -> {
              Map<String, Object> cmd = new HashMap<>();
              cmd.put("cmd", "LEADERBOARD");
              cmd.put("limit", 10);
              gateway.sendJson(cmd);
              long start = System.currentTimeMillis();
              while (System.currentTimeMillis() - start < 5000) {
                String resp = gateway.pollMessage();
                if (resp != null) {
                  try {
                    JsonNode root = mapper.readTree(resp);
                    if ("LEADERBOARD".equals(root.get("cmd").asText())) {
                      updateTable(root);
                      return;
                    }
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                }
                try {
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  break;
                }
              }
              SwingUtilities.invokeLater(
                  () -> {
                    tableModel.setRowCount(0);
                    tableModel.addRow(new Object[] {"Failed to load leaderboard", "", ""});
                  });
            })
        .start();
  }

  private void updateTable(JsonNode root) {
    SwingUtilities.invokeLater(
        () -> {
          tableModel.setRowCount(0);
          JsonNode entries = root.get("leaderboard");
          if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
              int rank = entry.get("rank").asInt();
              String username = entry.get("username").asText();
              int score = entry.get("score").asInt();
              tableModel.addRow(new Object[] {rank, username, score});
            }
          } else {
            tableModel.addRow(new Object[] {"No data", "", ""});
          }
        });
  }
}
