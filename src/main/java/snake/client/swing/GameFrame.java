package snake.client.swing;

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import snake.client.GatewayClient;

public class GameFrame extends JFrame {
  private final GameApp app;
  private final GatewayClient gateway;
  private GameCanvas canvas;
  private volatile boolean running = true;
  private int currentRoomId = -1;
  private int currentScore = 0;

  public GameFrame(GameApp app) {
    this.app = app;
    this.gateway = app.getGatewayClient();
    setTitle("Snake Game - " + app.getUsername());
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    setSize(800, 600);
    setLocationRelativeTo(null);
    setResizable(false);

    canvas = new GameCanvas();
    add(canvas, BorderLayout.CENTER);

    addKeyListener(
        new KeyAdapter() {
          @Override
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
                gateway.sendCommand("QUIT");
                dispose();
                app.showRoomList();
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

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            gateway.sendCommand("QUIT");
            gateway.setGameStateListener(null);
            gateway.setDeathListener(null);
            running = false;
            app.showRoomList();
          }
        });
  }

  @Override
  public void dispose() {
    running = false;
    super.dispose();
  }
}
