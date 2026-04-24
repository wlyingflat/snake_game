package snake.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import snake.base.Direction;
import snake.base.GameStateData;
import snake.base.Position;

public class LocalGameState {

  private final Map<String, PlayerData> players = new LinkedHashMap<>();
  private Position food;
  private int roomId;
  private Position[] obstacles; // 初始化时通过全量快照设置，之后不变
  private int obstacleCount;
  private int activePlayers;
  private int totalPlayers;

  // 内部玩家状态
  static class PlayerData {
    final List<Position> body = new ArrayList<>();
    int length;
    Direction direction;
    int score;
    boolean isDead;

    PlayerData() {}
  }

  // 通过全量快照覆盖
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

  // 应用差分更新
  public void applyDiff(JsonNode diffRoot) {
    if (diffRoot == null) return;
    JsonNode changes = diffRoot.get("changes");
    if (changes == null) return;

    // 食物
    if (changes.has("food")) {
      JsonNode f = changes.get("food");
      this.food = new Position(f.get("x").asInt(), f.get("y").asInt());
    }

    // 移除玩家
    if (changes.has("removedPlayers")) {
      JsonNode arr = changes.get("removedPlayers");
      for (JsonNode nameNode : arr) {
        players.remove(nameNode.asText());
      }
    }

    // 死亡玩家
    if (changes.has("died")) {
      JsonNode arr = changes.get("died");
      for (JsonNode nameNode : arr) {
        PlayerData pd = players.get(nameNode.asText());
        if (pd != null) {
          pd.isDead = true;
        }
      }
    }

    // 新玩家（包含完整身体信息）
    if (changes.has("newPlayers")) {
      JsonNode arr = changes.get("newPlayers");
      for (JsonNode node : arr) {
        String name = node.get("name").asText();
        PlayerData pd = new PlayerData();
        // 读取 body 数组
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

    // 存活玩家移动
    if (changes.has("players")) {
      JsonNode playersDiff = changes.get("players");
      Iterator<Map.Entry<String, JsonNode>> fields = playersDiff.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String name = entry.getKey();
        JsonNode pdNode = entry.getValue();
        PlayerData pd = players.get(name);
        if (pd == null || pd.isDead) continue;

        // 插入新头
        JsonNode headNode = pdNode.get("newHead");
        Position newHead = new Position(headNode.get("x").asInt(), headNode.get("y").asInt());
        pd.body.add(0, newHead);

        // 移除尾巴
        if (pdNode.get("removeTail").asBoolean() && pd.body.size() > 1) {
          pd.body.remove(pd.body.size() - 1);
        }

        pd.length = pdNode.get("length").asInt();
        pd.score = pd.length - 1; // ← 新增：根据长度实时推算分数
      }
    }

    // 重新计算活跃玩家数（简化起见）
    activePlayers = 0;
    for (PlayerData pd : players.values()) {
      if (!pd.isDead) activePlayers++;
    }
    totalPlayers = players.size();
  }

  // 生成用于渲染的 GameStateData
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
      pi.name = null; // 将在下面设置
      pi.head = pd.body.get(0);
      pi.body = pd.body.toArray(new Position[0]);
      pi.length = pd.length;
      pi.direction = pd.direction;
      pi.score = pd.score;
      pi.isDead = false;
      data.players[i] = pi;
    }
    // 直接设置 name 等
    for (int i = 0; i < count; i++) {
      data.players[i].name = (String) players.keySet().toArray()[i]; // 顺序可能不稳定，但用于渲染足够
    }

    // 更严谨的做法是遍历 players 的 entry set
    int idx = 0;
    for (Map.Entry<String, PlayerData> entry : players.entrySet()) {
      if (idx >= count) break;
      if (!entry.getValue().isDead) {
        data.players[idx].name = entry.getKey();
        idx++;
      }
    }

    data.activePlayers = this.activePlayers;
    data.totalPlayers = this.totalPlayers;
    return data;
  }
}
