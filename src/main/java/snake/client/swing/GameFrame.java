package snake.client.swing;

import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import javax.swing.*;
import snake.client.AgarBall;
import snake.client.GatewayClient;

public class GameFrame extends JFrame {
  private final GameApp app;
  private final GatewayClient gateway;
  private AgarCanvas canvas;
  private JLabel massLabel, roomLabel;
  private int currentRoomId = -1;
  private float targetX, targetY;
  private boolean movePending = false;
  private Timer moveTimer;

  public GameFrame(GameApp app) {
    this.app = app;
    this.gateway = app.getGatewayClient();
    initUI();
    setupGameListeners();
    setupInput();
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            cleanupAndExit();
          }

          @Override
          public void windowOpened(WindowEvent e) {
            // 确保窗口打开后画布获得键盘焦点
            canvas.requestFocusInWindow();
          }
        });
    // 立即请求一次焦点
    canvas.requestFocusInWindow();
  }

  private void initUI() {
    setTitle("Agar Battle - " + app.getUsername());
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    setLayout(new BorderLayout());

    // 顶部状态栏
    JPanel topPanel = new JPanel(new BorderLayout());
    topPanel.setBackground(new Color(30, 30, 50));
    topPanel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
    massLabel = new JLabel("Mass: 0");
    massLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
    massLabel.setForeground(new Color(220, 220, 100));
    roomLabel = new JLabel("Room: --");
    roomLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    roomLabel.setForeground(Color.LIGHT_GRAY);
    JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
    leftPanel.setOpaque(false);
    leftPanel.add(massLabel);
    leftPanel.add(roomLabel);
    topPanel.add(leftPanel, BorderLayout.WEST);
    add(topPanel, BorderLayout.NORTH);

    canvas = new AgarCanvas();
    add(canvas, BorderLayout.CENTER);
    pack();
    setLocationRelativeTo(null);
    setResizable(true);
    // 注意：已经通过上面的 requestFocus 请求了焦点
  }

  private void setupGameListeners() {
    gateway.setAgarFrameListener(
        data -> {
          SwingUtilities.invokeLater(
              () -> {
                String myName = app.getUsername();
                canvas.updateFrame(data.balls, data.foods, myName);
                float totalMass = 0;
                for (AgarBall b : data.balls) {
                  if (b.username.equals(myName)) {
                    totalMass += b.mass;
                  }
                }
                massLabel.setText("Mass: " + (int) totalMass);
              });
        });

    gateway.setDeathListener(
        () -> {
          SwingUtilities.invokeLater(
              () -> {
                gateway.setAgarFrameListener(null);
                int choice =
                    JOptionPane.showOptionDialog(
                        GameFrame.this,
                        "You have been eaten!",
                        "Game Over",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        new String[] {"Rejoin Room", "Back to Lobby"},
                        "Rejoin Room");
                if (choice == 0) {
                  gateway.sendJson(Map.of("cmd", "JOIN", "roomId", currentRoomId));
                  SwingUtilities.invokeLater(
                      () -> {
                        dispose();
                        app.showGame();
                      });
                } else {
                  dispose();
                  app.showRoomList();
                }
              });
        });
  }

  private void setupInput() {
    // 鼠标移动设置目标
    canvas.addMouseMotionListener(
        new MouseMotionAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            java.awt.geom.Point2D.Float world = canvas.screenToWorld(e.getX(), e.getY());
            if (world != null) {
              targetX = world.x;
              targetY = world.y;
              movePending = true;
            }
          }
        });

    // 键盘操作：空格分裂，W弹出，Q退出
    canvas.addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_SPACE) {
              gateway.sendSplit(targetX, targetY);
            } else if (e.getKeyCode() == KeyEvent.VK_W) {
              gateway.sendEject(targetX, targetY);
            } else if (e.getKeyCode() == KeyEvent.VK_Q) {
              quitToLobby();
            }
          }
        });

    // 定时器发送移动命令（降低发送频率）
    moveTimer =
        new Timer(
            50,
            e -> {
              if (movePending) {
                gateway.sendMove(targetX, targetY);
                movePending = false;
              }
            });
    moveTimer.start();
  }

  private void quitToLobby() {
    gateway.sendCommand("LEAVE");
    cleanupAndExit();
  }

  private void cleanupAndExit() {
    if (moveTimer != null) moveTimer.stop();
    gateway.setAgarFrameListener(null);
    gateway.setDeathListener(null);
    dispose();
    app.showRoomList();
  }

  @Override
  public void dispose() {
    if (moveTimer != null) moveTimer.stop();
    super.dispose();
  }
}
