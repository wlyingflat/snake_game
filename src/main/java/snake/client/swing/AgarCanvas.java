// snake/client/swing/AgarCanvas.java
package snake.client.swing;

import java.awt.*;
import java.awt.geom.*;
import java.util.List;
import javax.swing.*;
import snake.client.AgarBall;
import snake.client.Config;

public class AgarCanvas extends JPanel {
  private static final Color[] BALL_COLORS = {
    new Color(0xE5, 0x39, 0x35),
    new Color(0x1E, 0x88, 0xE5),
    new Color(0x43, 0xA0, 0x47),
    new Color(0xFB, 0x8C, 0x00),
    new Color(0x8E, 0x24, 0xAA),
    new Color(0x00, 0xAC, 0xC1),
    new Color(0xE5, 0x39, 0x9E),
    new Color(0x7C, 0xB3, 0x42),
  };

  private List<AgarBall> balls;
  private List<float[]> foods;
  private String myName;
  private float cameraX, cameraY;
  private float scale = 1.0f;

  public AgarCanvas() {
    setPreferredSize(new Dimension(Config.VIEW_WIDTH, Config.VIEW_HEIGHT));
    setBackground(new Color(0x0D, 0x0D, 0x1A));
    setFocusable(true);
  }

  public void updateFrame(List<AgarBall> balls, List<float[]> foods, String myName) {
    this.balls = balls;
    this.foods = foods;
    this.myName = myName;
    updateCamera();
    repaint();
  }

  private void updateCamera() {
    if (balls == null || myName == null) return;
    float sumX = 0, sumY = 0, totalMass = 0;
    for (AgarBall b : balls) {
      if (b.username.equals(myName)) {
        sumX += b.x * b.mass;
        sumY += b.y * b.mass;
        totalMass += b.mass;
      }
    }
    if (totalMass > 0) {
      cameraX = sumX / totalMass;
      cameraY = sumY / totalMass;
      float desiredScale = 500f / (float) Math.sqrt(totalMass);
      scale = Math.max(0.3f, Math.min(1.5f, desiredScale));
    }
  }

  private Point worldToScreen(float wx, float wy) {
    int sx = (int) ((wx - cameraX) * scale + getWidth() / 2);
    int sy = (int) ((wy - cameraY) * scale + getHeight() / 2);
    return new Point(sx, sy);
  }

  private int ballScreenRadius(float mass) {
    return (int) (Math.sqrt(mass) * scale * 4f);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2d = (Graphics2D) g;
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    drawGrid(g2d);

    // 食物
    if (foods != null) {
      for (float[] f : foods) {
        Point p = worldToScreen(f[0], f[1]);
        if (p.x >= -10 && p.x <= getWidth() + 10 && p.y >= -10 && p.y <= getHeight() + 10) {
          g2d.setColor(new Color(0xFF, 0xE0, 0x82));
          g2d.fillOval(p.x - 2, p.y - 2, 4, 4);
        }
      }
    }

    // 玩家球 / 刺球
    if (balls != null) {
      for (AgarBall ball : balls) {
        Point p = worldToScreen(ball.x, ball.y);
        int r = ballScreenRadius(ball.mass);
        if (p.x < -r * 2 || p.x > getWidth() + r * 2 || p.y < -r * 2 || p.y > getHeight() + r * 2)
          continue;

        // 刺球特殊绘制
        if (ball.username.equals("SPIKE")) {
          drawSpikeBall(g2d, p.x, p.y, r);
          continue;
        }

        Color color = BALL_COLORS[Math.abs(ball.username.hashCode()) % BALL_COLORS.length];
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g2d.fillOval(p.x - r - 4, p.y - r - 4, 2 * (r + 4), 2 * (r + 4));
        RadialGradientPaint rgp =
            new RadialGradientPaint(
                p.x - r * 0.3f,
                p.y - r * 0.3f,
                r,
                new float[] {0f, 0.7f, 1f},
                new Color[] {color.brighter(), color, color.darker()});
        g2d.setPaint(rgp);
        g2d.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
        g2d.setColor(color.darker());
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);
        if (r > 10) {
          g2d.setColor(Color.WHITE);
          g2d.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, r / 3)));
          String name = ball.username;
          FontMetrics fm = g2d.getFontMetrics();
          int nameWidth = fm.stringWidth(name);
          g2d.drawString(name, p.x - nameWidth / 2, p.y + fm.getAscent() / 2 - 2);
        }
      }
    }
  }

  private void drawSpikeBall(Graphics2D g2d, int x, int y, int radius) {
    // 深红底色
    g2d.setColor(new Color(180, 40, 40));
    g2d.fillOval(x - radius, y - radius, 2 * radius, 2 * radius);
    // 尖刺
    g2d.setColor(Color.RED);
    g2d.setStroke(new BasicStroke(2f));
    for (int i = 0; i < 8; i++) {
      double angle = i * Math.PI / 4;
      int sx = x + (int) (Math.cos(angle) * (radius + 4));
      int sy = y + (int) (Math.sin(angle) * (radius + 4));
      int ex = x + (int) (Math.cos(angle) * (radius * 1.5));
      int ey = y + (int) (Math.sin(angle) * (radius * 1.5));
      g2d.drawLine(sx, sy, ex, ey);
    }
  }

  private void drawGrid(Graphics2D g2d) {
    g2d.setColor(new Color(40, 40, 70, 80));
    int step = 50;
    float worldStep = step * scale;
    float startX = (cameraX % step) * scale;
    float startY = (cameraY % step) * scale;
    for (float x = -startX % worldStep; x < getWidth(); x += worldStep) {
      g2d.drawLine((int) x, 0, (int) x, getHeight());
    }
    for (float y = -startY % worldStep; y < getHeight(); y += worldStep) {
      g2d.drawLine(0, (int) y, getWidth(), (int) y);
    }
  }

  public Point.Float screenToWorld(int sx, int sy) {
    float wx = (sx - getWidth() / 2f) / scale + cameraX;
    float wy = (sy - getHeight() / 2f) / scale + cameraY;
    return new Point.Float(wx, wy);
  }
}
