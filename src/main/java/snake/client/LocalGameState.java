package snake.client;

import java.util.*;
import snake.common.Direction;
import snake.common.GameStateData;
import snake.common.Position;
import snake.fbs.*;

public class LocalGameState {

  private final Map<String, PlayerData> players = new LinkedHashMap<>();
  private Position food;
  private int roomId;
  private Position[] obstacles;
  private int obstacleCount;
  private int activePlayers;
  private int totalPlayers;

  static class PlayerData {
    final List<Position> body = new ArrayList<>();
    int length;
    Direction direction;
    int score;
    boolean isDead;
  }

  // ---------- FlatBuffers 版本 ----------
  public void applyFbsFullState(snake.fbs.GameState state) {
    players.clear();
    this.roomId = state.roomId();

    // 食物
    snake.fbs.Position fbFood = state.food();
    this.food = new Position(fbFood.x(), fbFood.y());

    // 障碍物
    int obsLen = state.obstaclesLength();
    this.obstacleCount = obsLen;
    if (this.obstacles == null) {
      this.obstacles = new Position[Config.OBSTACLE_COUNT];
    }
    for (int i = 0; i < obsLen; i++) {
      snake.fbs.Position obs = state.obstacles(i);
      this.obstacles[i] = new Position(obs.x(), obs.y());
    }

    // 玩家
    int playerCount = state.playersLength();
    for (int i = 0; i < playerCount; i++) {
      snake.fbs.Player fbPlayer = state.players(i);
      PlayerData pd = new PlayerData();
      pd.isDead = fbPlayer.isDead();
      pd.length = fbPlayer.length();
      pd.direction = convertDirection(fbPlayer.direction());
      pd.score = fbPlayer.score();

      int bodyLen = fbPlayer.bodyLength();
      for (int j = 0; j < bodyLen; j++) {
        snake.fbs.Position seg = fbPlayer.body(j);
        pd.body.add(new Position(seg.x(), seg.y()));
      }
      players.put(fbPlayer.name(), pd);
    }
    this.activePlayers = state.activePlayers();
    this.totalPlayers = state.totalPlayers();
  }

  public void applyFbsDiffState(snake.fbs.GameStateDiff diff) {
    // 食物
    snake.fbs.Position fbFood = diff.food();
    if (fbFood != null) {
      this.food = new Position(fbFood.x(), fbFood.y());
    }

    // 死亡
    for (int i = 0; i < diff.diedLength(); i++) {
      String name = diff.died(i);
      PlayerData pd = players.get(name);
      if (pd != null) pd.isDead = true;
    }

    // 移除
    for (int i = 0; i < diff.removedPlayersLength(); i++) {
      players.remove(diff.removedPlayers(i));
    }

    // 新玩家
    for (int i = 0; i < diff.newPlayersLength(); i++) {
      snake.fbs.Player fbPlayer = diff.newPlayers(i);
      PlayerData pd = new PlayerData();
      pd.isDead = fbPlayer.isDead();
      pd.length = fbPlayer.length();
      pd.direction = convertDirection(fbPlayer.direction());
      pd.score = fbPlayer.score();

      int bodyLen = fbPlayer.bodyLength();
      for (int j = 0; j < bodyLen; j++) {
        snake.fbs.Position seg = fbPlayer.body(j);
        pd.body.add(new Position(seg.x(), seg.y()));
      }
      players.put(fbPlayer.name(), pd);
    }

    // 玩家差分 (KeyValue 列表)
    for (int i = 0; i < diff.playersDiffLength(); i++) {
      KeyValue kv = diff.playersDiff(i);
      String name = kv.key();
      PlayerDiff pdiff = kv.value();
      PlayerData pd = players.get(name);
      if (pd == null || pd.isDead) continue;

      snake.fbs.Position newHead = pdiff.newHead();
      pd.body.add(0, new Position(newHead.x(), newHead.y()));
      if (pdiff.removeTail() && pd.body.size() > 1) {
        pd.body.remove(pd.body.size() - 1);
      }
      pd.length = pdiff.length();
      pd.score = pd.length - 1;
    }

    activePlayers = 0;
    for (PlayerData pd : players.values()) {
      if (!pd.isDead) activePlayers++;
    }
    totalPlayers = players.size();
  }

  private Direction convertDirection(byte dir) {
    return switch (dir) {
      case snake.fbs.Direction.UP -> Direction.UP;
      case snake.fbs.Direction.DOWN -> Direction.DOWN;
      case snake.fbs.Direction.LEFT -> Direction.LEFT;
      default -> Direction.RIGHT;
    };
  }

  // ---------- 构造渲染用的 GameStateData ----------
  public GameStateData toGameStateData() {
    GameStateData data = new GameStateData();
    data.roomId = this.roomId;
    data.food = this.food;
    data.obstacleCount = this.obstacleCount;
    for (int i = 0; i < this.obstacleCount; i++) {
      data.obstacles[i] = this.obstacles[i];
    }

    List<PlayerData> activeList = new ArrayList<>();
    for (PlayerData pd : players.values()) {
      if (!pd.isDead) {
        activeList.add(pd);
      }
    }

    int count = Math.min(activeList.size(), Config.MAX_PLAYERS_PER_ROOM);
    data.playerCount = count;
    for (int i = 0; i < count; i++) {
      PlayerData pd = activeList.get(i);
      GameStateData.PlayerInfo pi = new GameStateData.PlayerInfo();
      pi.head = pd.body.get(0);
      pi.body = pd.body.toArray(new Position[0]);
      pi.length = pd.length;
      pi.direction = pd.direction;
      pi.score = pd.score;
      pi.isDead = false;
      data.players[i] = pi;
    }

    int idx = 0;
    for (Map.Entry<String, PlayerData> entry : players.entrySet()) {
      if (entry.getValue().isDead) continue;
      if (idx >= count) break;
      data.players[idx].name = entry.getKey();
      idx++;
    }

    data.activePlayers = this.activePlayers;
    data.totalPlayers = this.totalPlayers;
    return data;
  }
}
