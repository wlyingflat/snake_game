package snake.client.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import snake.client.GatewayClient;

public class LeaderboardDialog extends JDialog {
  private final GatewayClient gateway;
  private final ObjectMapper mapper = new ObjectMapper();
  private JTable table;
  private DefaultTableModel tableModel;

  public LeaderboardDialog(Frame owner, GatewayClient gateway) {
    super(owner, "Leaderboard", true);
    this.gateway = gateway;
    setSize(400, 300);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());

    // 表格模型
    String[] columns = {"Rank", "Username", "Score"};
    tableModel =
        new DefaultTableModel(columns, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    table = new JTable(tableModel);
    table.setFillsViewportHeight(true);
    table.setRowHeight(25);
    add(new JScrollPane(table), BorderLayout.CENTER);

    // 底部按钮
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JButton refreshBtn = new JButton("Refresh");
    JButton closeBtn = new JButton("Close");
    buttonPanel.add(refreshBtn);
    buttonPanel.add(closeBtn);
    add(buttonPanel, BorderLayout.SOUTH);

    // 事件
    refreshBtn.addActionListener(e -> fetchLeaderboard());
    closeBtn.addActionListener(e -> dispose());

    // 初始加载
    fetchLeaderboard();
  }

  private void fetchLeaderboard() {
    // 显示加载中
    tableModel.setRowCount(0);
    tableModel.addRow(new Object[] {"Loading...", "", ""});

    new Thread(
            () -> {
              // 发送命令，默认获取前10名
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
              // 超时或错误
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
