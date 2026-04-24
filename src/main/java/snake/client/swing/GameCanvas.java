package snake.client.swing;

import java.awt.*;
import javax.swing.*;
import snake.base.GameStateData;
import snake.base.Position;

public class GameCanvas extends JPanel {
  private static final int CELL_SIZE = 20;
  private static final int MAP_WIDTH = 40;
  private static final int MAP_HEIGHT = 20;

  private GameStateData state;
  private String myName;

  public GameCanvas() {
    setPreferredSize(new Dimension(MAP_WIDTH * CELL_SIZE, MAP_HEIGHT * CELL_SIZE));
    setBackground(Color.BLACK);
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

    // 网格
    g.setColor(Color.DARK_GRAY);
    for (int i = 0; i <= MAP_WIDTH; i++) {
      g.drawLine(i * CELL_SIZE, 0, i * CELL_SIZE, MAP_HEIGHT * CELL_SIZE);
    }
    for (int i = 0; i <= MAP_HEIGHT; i++) {
      g.drawLine(0, i * CELL_SIZE, MAP_WIDTH * CELL_SIZE, i * CELL_SIZE);
    }

    // 墙壁
    g.setColor(Color.GRAY);
    for (int y = 0; y < MAP_HEIGHT; y++) {
      for (int x = 0; x < MAP_WIDTH; x++) {
        if (x == 0 || x == MAP_WIDTH - 1 || y == 0 || y == MAP_HEIGHT - 1) {
          g.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        }
      }
    }

    // 障碍物
    g.setColor(Color.YELLOW);
    for (int i = 0; i < state.obstacleCount; i++) {
      Position obs = state.obstacles[i];
      if (obs != null
          && obs.x > 0
          && obs.x < MAP_WIDTH - 1
          && obs.y > 0
          && obs.y < MAP_HEIGHT - 1) {
        g.fillRect(obs.x * CELL_SIZE, obs.y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
      }
    }

    // 食物
    g.setColor(Color.RED);
    if (state.food != null) {
      g.fillOval(state.food.x * CELL_SIZE, state.food.y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
    }

    // 蛇
    for (int i = 0; i < state.playerCount; i++) {
      GameStateData.PlayerInfo p = state.players[i];
      if (p == null || p.isDead) continue;
      boolean isMe = p.name.equals(myName);
      for (int j = 0; j < p.length; j++) {
        Position seg = p.body[j];
        if (seg == null) continue;
        if (j == 0) {
          g.setColor(isMe ? Color.GREEN : Color.CYAN);
        } else {
          g.setColor(isMe ? new Color(0, 100, 0) : Color.BLUE);
        }
        g.fillRect(seg.x * CELL_SIZE, seg.y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        if (j == 0) {
          g.setColor(Color.WHITE);
          int eyeSize = CELL_SIZE / 4;
          g.fillOval(
              seg.x * CELL_SIZE + CELL_SIZE / 3,
              seg.y * CELL_SIZE + CELL_SIZE / 3,
              eyeSize,
              eyeSize);
          g.fillOval(
              seg.x * CELL_SIZE + CELL_SIZE - CELL_SIZE / 3 - eyeSize,
              seg.y * CELL_SIZE + CELL_SIZE / 3,
              eyeSize,
              eyeSize);
        }
      }
    }

    // 分数
    g.setColor(Color.WHITE);
    for (int i = 0; i < state.playerCount; i++) {
      if (state.players[i].name.equals(myName)) {
        g.drawString("Score: " + state.players[i].score, 10, 20);
        break;
      }
    }
  }
}
