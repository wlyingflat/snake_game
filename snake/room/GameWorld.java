package snake.room;

import java.util.*;
import snake.common.*;
import snake.util.Logger;

public class GameWorld {
  public char[][] map = new char[Config.MAP_HEIGHT][Config.MAP_WIDTH];
  public Position food;
  public Position[] obstacles = new Position[Config.OBSTACLE_COUNT];
  public int totalPlayers = 0;
  public int activePlayers = 0;
  public boolean initialDelayDone = false;

  public void init() {
    // 初始化地图为空格
    for (int y = 0; y < Config.MAP_HEIGHT; y++) {
      for (int x = 0; x < Config.MAP_WIDTH; x++) {
        map[y][x] = ' ';
      }
    }

    // 绘制边界
    for (int x = 0; x < Config.MAP_WIDTH; x++) {
      map[0][x] = '#';
      map[Config.MAP_HEIGHT - 1][x] = '#';
    }
    for (int y = 0; y < Config.MAP_HEIGHT; y++) {
      map[y][0] = '#';
      map[y][Config.MAP_WIDTH - 1] = '#';
    }

    // 生成障碍物 - 改进算法，确保每个障碍物都有效且随机
    Random rand = new Random();
    List<Position> freePositions = getFreePositions();

    int obstacleCount = Math.min(Config.OBSTACLE_COUNT, freePositions.size());
    if (obstacleCount < Config.OBSTACLE_COUNT) {
      Logger.warn("Not enough free positions for all obstacles, placing " + obstacleCount);
    }

    Collections.shuffle(freePositions, rand);
    for (int i = 0; i < obstacleCount; i++) {
      Position pos = freePositions.get(i);
      obstacles[i] = pos;
      map[pos.y][pos.x] = 'X';
    }
    for (int i = obstacleCount; i < Config.OBSTACLE_COUNT; i++) {
      obstacles[i] = new Position(0, 0);
    }

    // 生成初始食物（避开障碍物）
    food = findInitialFoodPosition();
  }

  private List<Position> getFreePositions() {
    List<Position> positions = new ArrayList<>();
    for (int y = 1; y < Config.MAP_HEIGHT - 1; y++) {
      for (int x = 1; x < Config.MAP_WIDTH - 1; x++) {
        if (map[y][x] == ' ') {
          positions.add(new Position(x, y));
        }
      }
    }
    return positions;
  }

  private Position findInitialFoodPosition() {
    Random rand = new Random();
    Position pos;
    int attempts = 0;
    do {
      pos =
          new Position(
              rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
      attempts++;
      if (attempts > Config.MAX_SPAWN_ATTEMPTS) {
        for (int y = 1; y < Config.MAP_HEIGHT - 1; y++) {
          for (int x = 1; x < Config.MAP_WIDTH - 1; x++) {
            if (map[y][x] != 'X') {
              pos = new Position(x, y);
              break;
            }
          }
        }
        break;
      }
    } while (isObstacle(pos));
    return pos;
  }

  private boolean isObstacle(Position pos) {
    for (Position obs : obstacles) {
      if (obs != null && obs.x == pos.x && obs.y == pos.y) return true;
    }
    return false;
  }
}
