package snake.client;

import com.fasterxml.jackson.databind.JsonNode;
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

  // ---------- JSON 兼容方法 ----------
  public void applyFullState(GameStateData data) {
    players.clear();
    this.roomId = data.roomId;
    this.food = data.food;
    this.obstacleCount = data.obstacleCount;
    if (this.obstacles == null) {
      this.obstacles = new Position[Config.OBSTACLE_COUNT];
    }
    for (int i = 0; i < data.obstacleCount; i++) {
      this.obstacles[i] = data.obstacles[i];
    }
    for (int i = 0; i < data.playerCount; i++) {
      GameStateData.PlayerInfo pi = data.players[i];
      if (pi == null) continue;
      PlayerData pd = new PlayerData();
      pd.body.addAll(Arrays.asList(pi.body).subList(0, pi.length));
      pd.length = pi.length;
      pd.direction = pi.direction;
      pd.score = pi.score;
      pd.isDead = pi.isDead;
      players.put(pi.name, pd);
    }
    this.activePlayers = data.activePlayers;
    this.totalPlayers = data.totalPlayers;
  }

  public void applyDiff(JsonNode diffRoot) {
    if (diffRoot == null) return;
    JsonNode changes = diffRoot.get("changes");
    if (changes == null) return;

    if (changes.has("food")) {
      JsonNode f = changes.get("food");
      this.food = new Position(f.get("x").asInt(), f.get("y").asInt());
    }
    if (changes.has("removedPlayers")) {
      JsonNode arr = changes.get("removedPlayers");
      for (JsonNode nameNode : arr) {
        players.remove(nameNode.asText());
      }
    }
    if (changes.has("died")) {
      JsonNode arr = changes.get("died");
      for (JsonNode nameNode : arr) {
        PlayerData pd = players.get(nameNode.asText());
        if (pd != null) pd.isDead = true;
      }
    }
    if (changes.has("newPlayers")) {
      JsonNode arr = changes.get("newPlayers");
      for (JsonNode node : arr) {
        String name = node.get("name").asText();
        PlayerData pd = new PlayerData();
        JsonNode bodyArr = node.get("body");
        if (bodyArr != null && bodyArr.isArray()) {
          for (JsonNode seg : bodyArr) {
            pd.body.add(new Position(seg.get("x").asInt(), seg.get("y").asInt()));
          }
        }
        pd.length = node.get("length").asInt();
        pd.direction = Direction.valueOf(node.get("direction").asText());
        pd.score = node.has("score") ? node.get("score").asInt() : 0;
        pd.isDead = node.has("isDead") ? node.get("isDead").asBoolean() : false;
        players.put(name, pd);
      }
    }
    if (changes.has("players")) {
      JsonNode playersDiff = changes.get("players");
      Iterator<Map.Entry<String, JsonNode>> fields = playersDiff.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String name = entry.getKey();
        JsonNode pdNode = entry.getValue();
        PlayerData pd = players.get(name);
        if (pd == null || pd.isDead) continue;
        JsonNode headNode = pdNode.get("newHead");
        Position newHead = new Position(headNode.get("x").asInt(), headNode.get("y").asInt());
        pd.body.add(0, newHead);
        if (pdNode.get("removeTail").asBoolean() && pd.body.size() > 1) {
          pd.body.remove(pd.body.size() - 1);
        }
        pd.length = pdNode.get("length").asInt();
        pd.score = pd.length - 1;
      }
    }
    activePlayers = 0;
    for (PlayerData pd : players.values()) {
      if (!pd.isDead) activePlayers++;
    }
    totalPlayers = players.size();
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
