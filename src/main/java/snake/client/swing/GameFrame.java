// GameFrame.java - 美化版（添加顶部状态栏，优化布局）
package snake.client.swing;

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.*;
import snake.client.GatewayClient;

public class GameFrame extends JFrame {
  private final GameApp app;
  private final GatewayClient gateway;
  private GameCanvas canvas;
  private volatile boolean running = true;
  private int currentRoomId = -1;
  private int currentScore = 0;
  private JLabel scoreLabel, roomLabel, infoLabel;

  public GameFrame(GameApp app) {
    this.app = app;
    this.gateway = app.getGatewayClient();
    initUI();
    setupGameListeners();
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            cleanupAndExit();
          }
        });
  }

  private void initUI() {
    setTitle("Snake Game - " + app.getUsername());
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    setLayout(new BorderLayout());
    setBackground(new Color(20, 20, 35));

    // 顶部状态栏
    JPanel topPanel = new JPanel(new BorderLayout());
    topPanel.setBackground(new Color(30, 30, 50));
    topPanel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
    scoreLabel = new JLabel("Score: 0");
    scoreLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
    scoreLabel.setForeground(new Color(220, 220, 100));
    roomLabel = new JLabel("Room: --");
    roomLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    roomLabel.setForeground(Color.LIGHT_GRAY);
    infoLabel = new JLabel("Use ↑ ↓ ← →  or  WASD    Q = Quit");
    infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    infoLabel.setForeground(new Color(150, 150, 180));
    JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
    leftPanel.setOpaque(false);
    leftPanel.add(scoreLabel);
    leftPanel.add(roomLabel);
    JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    rightPanel.setOpaque(false);
    rightPanel.add(infoLabel);
    topPanel.add(leftPanel, BorderLayout.WEST);
    topPanel.add(rightPanel, BorderLayout.EAST);
    add(topPanel, BorderLayout.NORTH);

    canvas = new GameCanvas();
    canvas.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 120), 2));
    add(canvas, BorderLayout.CENTER);

    // 键盘监听
    addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            String dir = null;
            switch (e.getKeyCode()) {
              case KeyEvent.VK_UP:
              case KeyEvent.VK_W:
                dir = "UP";
                break;
              case KeyEvent.VK_DOWN:
              case KeyEvent.VK_S:
                dir = "DOWN";
                break;
              case KeyEvent.VK_LEFT:
              case KeyEvent.VK_A:
                dir = "LEFT";
                break;
              case KeyEvent.VK_RIGHT:
              case KeyEvent.VK_D:
                dir = "RIGHT";
                break;
              case KeyEvent.VK_Q:
                quitToLobby();
                return;
            }
            if (dir != null) {
              Map<String, Object> msg = new HashMap<>();
              msg.put("cmd", "INPUT");
              msg.put("direction", dir);
              gateway.sendJson(msg);
            }
          }
        });
    setFocusable(true);
    requestFocus();
    pack();
    setLocationRelativeTo(null);
    setResizable(false);
  }

  private void updateTopBar(int score, int roomId) {
    SwingUtilities.invokeLater(
        () -> {
          scoreLabel.setText("Score: " + score);
          roomLabel.setText("Room: " + (roomId >= 0 ? roomId : "--"));
        });
  }

  private void setupGameListeners() {
    gateway.setGameStateListener(
        (json, stateData) -> {
          if (stateData != null) {
            SwingUtilities.invokeLater(
                () -> {
                  canvas.updateState(stateData, app.getUsername());
                  currentRoomId = stateData.roomId;
                  for (int i = 0; i < stateData.playerCount; i++) {
                    if (stateData.players[i].name.equals(app.getUsername())) {
                      currentScore = stateData.players[i].score;
                      updateTopBar(currentScore, currentRoomId);
                      break;
                    }
                  }
                });
          }
        });

    gateway.setDeathListener(
        () -> {
          SwingUtilities.invokeLater(
              () -> {
                gateway.setGameStateListener(null);
                int choice =
                    JOptionPane.showOptionDialog(
                        GameFrame.this,
                        "You died! Score: " + currentScore,
                        "Game Over",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        new String[] {"Rejoin Room", "Back to Lobby"},
                        "Rejoin Room");
                if (choice == 0) {
                  gateway.sendJson(Map.of("cmd", "JOIN", "roomId", currentRoomId));
                  new Thread(
                          () -> {
                            long start = System.currentTimeMillis();
                            while (System.currentTimeMillis() - start < 5000) {
                              String resp = gateway.pollMessage();
                              if (resp != null && resp.contains("\"cmd\":\"JOIN_OK\"")) {
                                SwingUtilities.invokeLater(
                                    () -> {
                                      dispose();
                                      app.showGame();
                                    });
                                return;
                              }
                              try {
                                Thread.sleep(100);
                              } catch (InterruptedException ignored) {
                              }
                            }
                            SwingUtilities.invokeLater(
                                () -> {
                                  JOptionPane.showMessageDialog(GameFrame.this, "Rejoin failed.");
                                  dispose();
                                  app.showRoomList();
                                });
                          })
                      .start();
                } else {
                  dispose();
                  app.showRoomList();
                }
              });
        });
  }

  private void quitToLobby() {
    gateway.sendCommand("QUIT");
    cleanupAndExit();
  }

  private void cleanupAndExit() {
    gateway.setGameStateListener(null);
    gateway.setDeathListener(null);
    running = false;
    dispose();
    app.showRoomList();
  }

  @Override
  public void dispose() {
    running = false;
    super.dispose();
  }
}
