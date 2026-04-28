// GameCanvas.java - 美化版（高级绘图效果）
package snake.client.swing;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.*;
import snake.client.Config;
import snake.common.GameStateData;
import snake.common.Position;

public class GameCanvas extends JPanel {
  private static final int CELL_SIZE = 20;
  private GameStateData state;
  private String myName;

  public GameCanvas() {
    setPreferredSize(new Dimension(Config.MAP_WIDTH * CELL_SIZE, Config.MAP_HEIGHT * CELL_SIZE));
    setBackground(new Color(15, 15, 25));
  }

  public void updateState(GameStateData state, String myName) {
    this.state = state;
    this.myName = myName;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (state == null) return;
    Graphics2D g2d = (Graphics2D) g;
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

    int mapW = Config.MAP_WIDTH;
    int mapH = Config.MAP_HEIGHT;

    // 绘制网格（细线）
    g2d.setColor(new Color(40, 40, 60));
    for (int i = 0; i <= mapW; i++) {
      g2d.drawLine(i * CELL_SIZE, 0, i * CELL_SIZE, mapH * CELL_SIZE);
      g2d.drawLine(0, i * CELL_SIZE, mapW * CELL_SIZE, i * CELL_SIZE);
    }

    // 墙壁（渐变金属感）
    g2d.setColor(new Color(70, 70, 90));
    for (int y = 0; y < mapH; y++) {
      for (int x = 0; x < mapW; x++) {
        if (x == 0 || x == mapW - 1 || y == 0 || y == mapH - 1) {
          g2d.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        }
      }
    }

    // 障碍物（带发光边缘）
    g2d.setColor(new Color(255, 215, 0));
    for (int i = 0; i < state.obstacleCount; i++) {
      Position obs = state.obstacles[i];
      if (obs != null && obs.x > 0 && obs.x < mapW - 1 && obs.y > 0 && obs.y < mapH - 1) {
        g2d.fillRoundRect(obs.x * CELL_SIZE, obs.y * CELL_SIZE, CELL_SIZE, CELL_SIZE, 4, 4);
        g2d.setColor(new Color(255, 235, 120));
        g2d.drawRoundRect(obs.x * CELL_SIZE, obs.y * CELL_SIZE, CELL_SIZE, CELL_SIZE, 4, 4);
        g2d.setColor(new Color(255, 215, 0));
      }
    }

    // 食物（发光+旋转渐变）
    if (state.food != null) {
      int x = state.food.x * CELL_SIZE;
      int y = state.food.y * CELL_SIZE;
      RadialGradientPaint rgp =
          new RadialGradientPaint(
              x + CELL_SIZE / 2f,
              y + CELL_SIZE / 2f,
              CELL_SIZE / 2f,
              new float[] {0f, 1f},
              new Color[] {Color.RED, new Color(160, 30, 30)});
      g2d.setPaint(rgp);
      g2d.fillOval(x, y, CELL_SIZE, CELL_SIZE);
      g2d.setColor(Color.WHITE);
      g2d.setStroke(new BasicStroke(1.2f));
      g2d.drawOval(x, y, CELL_SIZE, CELL_SIZE);
    }

    // 蛇（渐变身体+圆角）
    for (int i = 0; i < state.playerCount; i++) {
      GameStateData.PlayerInfo p = state.players[i];
      if (p == null || p.isDead) continue;
      boolean isMe = p.name.equals(myName);

      for (int j = 0; j < p.length; j++) {
        Position seg = p.body[j];
        if (seg == null) continue;
        int x = seg.x * CELL_SIZE;
        int y = seg.y * CELL_SIZE;
        if (j == 0) {
          // 头部渐变
          GradientPaint headGrad =
              new GradientPaint(
                  x,
                  y,
                  isMe ? new Color(80, 220, 80) : new Color(60, 180, 210),
                  x + CELL_SIZE,
                  y + CELL_SIZE,
                  isMe ? new Color(40, 140, 40) : new Color(30, 100, 140));
          g2d.setPaint(headGrad);
          g2d.fillRoundRect(x, y, CELL_SIZE, CELL_SIZE, 6, 6);
          // 眼睛
          g2d.setColor(Color.WHITE);
          int eyeSize = CELL_SIZE / 4;
          g2d.fillOval(x + CELL_SIZE / 3, y + CELL_SIZE / 3, eyeSize, eyeSize);
          g2d.fillOval(
              x + CELL_SIZE - CELL_SIZE / 3 - eyeSize, y + CELL_SIZE / 3, eyeSize, eyeSize);
          g2d.setColor(Color.BLACK);
          g2d.fillOval(x + CELL_SIZE / 3 + 2, y + CELL_SIZE / 3 + 1, eyeSize / 2, eyeSize / 2);
          g2d.fillOval(
              x + CELL_SIZE - CELL_SIZE / 3 - eyeSize + 1,
              y + CELL_SIZE / 3 + 1,
              eyeSize / 2,
              eyeSize / 2);
        } else {
          GradientPaint bodyGrad =
              new GradientPaint(
                  x,
                  y,
                  isMe ? new Color(50, 170, 50) : new Color(40, 130, 170),
                  x + CELL_SIZE,
                  y + CELL_SIZE,
                  isMe ? new Color(30, 100, 30) : new Color(30, 80, 110));
          g2d.setPaint(bodyGrad);
          g2d.fillRoundRect(x, y, CELL_SIZE, CELL_SIZE, 5, 5);
        }
      }
    }
  }
}
