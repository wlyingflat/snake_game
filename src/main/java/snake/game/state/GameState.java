package snake.game.state;

import java.util.*;
import snake.base.*;
import snake.game.event.GameStateDiff;
import snake.game.event.PlayerDiff;

public class GameState {
  private final int roomId;
  private final char[][] map = new char[Config.MAP_HEIGHT][Config.MAP_WIDTH];
  private Position food;
  private final Position[] obstacles = new Position[Config.OBSTACLE_COUNT];
  private final Map<String, Player> players = new HashMap<>();
  private int totalPlayers = 0;
  private int activePlayers = 0;
  private boolean initialDelayDone = false;
  private int tickCounter = 0;

  // ---------- 差分计算需要的历史状态 ----------
  private Position previousFood;
  private final Map<String, List<Position>> previousPlayerBodies = new HashMap<>();
  private int previousActivePlayers;
  private Set<String> previousPlayerNames = new HashSet<>();
  // 记录本 tick 新加入的玩家，用于发送全量快照
  private final Set<String> newPlayersThisTick = new HashSet<>();

  public GameState(int roomId) {
    this.roomId = roomId;
    initWorld();
    // 初始化 previous 状态为空，确保第一个 tick 能正确处理
    previousFood = new Position(food.x, food.y);
    previousActivePlayers = 0;
    previousPlayerNames = new HashSet<>();
  }

  private void initWorld() {
    for (int y = 0; y < Config.MAP_HEIGHT; y++) {
      for (int x = 0; x < Config.MAP_WIDTH; x++) {
        map[y][x] = ' ';
      }
    }
    for (int x = 0; x < Config.MAP_WIDTH; x++) {
      map[0][x] = '#';
      map[Config.MAP_HEIGHT - 1][x] = '#';
    }
    for (int y = 0; y < Config.MAP_HEIGHT; y++) {
      map[y][0] = '#';
      map[y][Config.MAP_WIDTH - 1] = '#';
    }

    Random rand = new Random();
    List<Position> freePositions = getFreePositions();
    int obstacleCount = Math.min(Config.OBSTACLE_COUNT, freePositions.size());
    Collections.shuffle(freePositions, rand);
    for (int i = 0; i < obstacleCount; i++) {
      Position pos = freePositions.get(i);
      obstacles[i] = pos;
      map[pos.y][pos.x] = 'X';
    }
    for (int i = obstacleCount; i < Config.OBSTACLE_COUNT; i++) {
      obstacles[i] = new Position(0, 0);
    }

    food = findSafeFoodPosition(null);
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

  private Position findSafeFoodPosition(Position exclude) {
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
            if (map[y][x] != 'X' && (exclude == null || (x != exclude.x || y != exclude.y))) {
              pos = new Position(x, y);
              break;
            }
          }
        }
        break;
      }
    } while (isObstacle(pos) || (exclude != null && pos.x == exclude.x && pos.y == exclude.y));
    return pos;
  }

  private boolean isObstacle(Position pos) {
    for (Position obs : obstacles) {
      if (obs != null && obs.x == pos.x && obs.y == pos.y) return true;
    }
    return false;
  }

  public boolean addPlayer(String username) {
    if (players.containsKey(username)) return false;
    if (totalPlayers >= Config.MAX_PLAYERS_PER_ROOM) return false;

    Player player = new Player();
    player.username = username;
    player.body = new ArrayList<>();
    Position start = findSafeSpawnPosition();
    player.body.add(start);
    player.length = 1;
    player.direction = Direction.values()[new Random().nextInt(4)];
    player.score = 0;
    player.isDead = false;
    players.put(username, player);
    totalPlayers++;
    activePlayers++;
    initialDelayDone = true;

    // 标记为新玩家，用于后续发送全量快照
    newPlayersThisTick.add(username);
    return true;
  }

  private Position findSafeSpawnPosition() {
    Random rand = new Random();
    Position pos;
    int attempts = 0;
    do {
      pos =
          new Position(
              rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
      attempts++;
      if (attempts > Config.MAX_SPAWN_ATTEMPTS) {
        pos = new Position(Config.MAP_WIDTH / 2, Config.MAP_HEIGHT / 2);
        break;
      }
    } while (isPositionOccupied(pos));
    return pos;
  }

  private boolean isPositionOccupied(Position pos) {
    if (pos.x <= 0 || pos.x >= Config.MAP_WIDTH - 1 || pos.y <= 0 || pos.y >= Config.MAP_HEIGHT - 1)
      return true;
    for (Position obs : obstacles) {
      if (obs != null && obs.x == pos.x && obs.y == pos.y) return true;
    }
    for (Player p : players.values()) {
      if (p.isDead) continue;
      for (Position seg : p.body) {
        if (seg.x == pos.x && seg.y == pos.y) return true;
      }
    }
    return false;
  }

  public void removePlayer(String username) {
    Player p = players.remove(username);
    if (p != null) {
      totalPlayers--;
      if (!p.isDead) activePlayers--;
      previousPlayerBodies.remove(username);
      previousPlayerNames.remove(username);
    }
  }

  public void updateDirection(String username, Direction dir) {
    Player p = players.get(username);
    if (p != null && !p.isDead) {
      p.direction = dir;
      Logger.getInstance().debug("Player " + username + " direction updated to " + dir);
    } else if (p != null && p.isDead) {
      Logger.getInstance().debug("Ignored direction for dead player: " + username);
    } else {
      Logger.getInstance().debug("Player not found: " + username);
    }
  }

  public void update() {
    if (!initialDelayDone) {
      if (++tickCounter >= Config.ROOM_INIT_DELAY_TICKS) {
        initialDelayDone = true;
      }
      return;
    }

    // 保存移动前状态，用于后续差分计算
    previousFood = new Position(food.x, food.y);
    for (Map.Entry<String, Player> entry : players.entrySet()) {
      previousPlayerBodies.put(entry.getKey(), new ArrayList<>(entry.getValue().body));
    }
    previousActivePlayers = activePlayers;
    previousPlayerNames = new HashSet<>(players.keySet());

    List<Player> playerList = new ArrayList<>(players.values());
    Map<Player, Position> nextHeads = new HashMap<>();
    Map<Player, Boolean> willDie = new HashMap<>();
    Map<Player, Boolean> willGrow = new HashMap<>();

    for (Player p : playerList) {
      Position next = calculateNextPosition(p);
      nextHeads.put(p, next);
      willGrow.put(p, next.x == food.x && next.y == food.y);
      willDie.put(p, checkCollision(p, next));
    }

    for (Player p : playerList) {
      if (willDie.get(p)) {
        p.isDead = true;
        activePlayers--;
        willGrow.put(p, false);
        // 死前位置仍保留在 previous 中，但计算时忽略
      }
    }

    boolean foodEaten = false;
    for (Player p : playerList) {
      if (p.isDead) continue;
      Position next = nextHeads.get(p);
      if (willGrow.get(p)) {
        p.body.add(0, next);
        p.length++;
        p.score++;
        foodEaten = true;
      } else {
        p.body.add(0, next);
        p.body.remove(p.body.size() - 1);
      }
    }

    if (foodEaten) {
      food = findSafeFoodPosition(food);
    }

    int active = 0;
    for (Player p : players.values()) {
      if (!p.isDead) active++;
    }
    activePlayers = active;
    tickCounter++;
  }

  private Position calculateNextPosition(Player p) {
    Position head = p.body.get(0);
    Position next = new Position(head.x, head.y);
    switch (p.direction) {
      case UP:
        next.y--;
        break;
      case DOWN:
        next.y++;
        break;
      case LEFT:
        next.x--;
        break;
      case RIGHT:
        next.x++;
        break;
    }
    return next;
  }

  private boolean checkCollision(Player p, Position next) {
    if (next.x <= 0
        || next.x >= Config.MAP_WIDTH - 1
        || next.y <= 0
        || next.y >= Config.MAP_HEIGHT - 1) return true;
    for (Position obs : obstacles) {
      if (obs != null && obs.x == next.x && obs.y == next.y) return true;
    }
    for (int i = 0; i < p.body.size(); i++) {
      if (next.x == p.body.get(i).x && next.y == p.body.get(i).y) return true;
    }
    for (Player other : players.values()) {
      if (other == p || other.isDead) continue;
      for (Position seg : other.body) {
        if (next.x == seg.x && next.y == seg.y) return true;
      }
    }
    return false;
  }

  /** 全量快照（用于新玩家或定期纠正） */
  public GameStateData snapshot(String clientUsername) {
    GameStateData data = new GameStateData();
    data.roomId = roomId;
    data.food = food;
    data.obstacleCount = Config.OBSTACLE_COUNT;
    for (int i = 0; i < Config.OBSTACLE_COUNT; i++) {
      data.obstacles[i] = obstacles[i];
    }
    List<Player> playerList = new ArrayList<>(players.values());
    int count = Math.min(playerList.size(), Config.MAX_PLAYERS_PER_ROOM);
    data.playerCount = count;
    for (int i = 0; i < count; i++) {
      Player p = playerList.get(i);
      GameStateData.PlayerInfo info = new GameStateData.PlayerInfo();
      info.name = p.username;
      info.head = p.body.get(0);
      info.body = p.body.toArray(new Position[0]);
      info.length = p.length;
      info.direction = p.direction;
      info.score = p.score;
      info.isDead = p.isDead;
      data.players[i] = info;
    }
    data.activePlayers = activePlayers;
    data.totalPlayers = totalPlayers;
    return data;
  }

  /** 检查是否有新玩家需要发送全量快照（调用后会自动清除标记） */
  public boolean hasNewPlayer() {
    boolean result = !newPlayersThisTick.isEmpty();
    newPlayersThisTick.clear();
    return result;
  }

  /** 计算本 tick 的差分数据 */
  public GameStateDiff computeDiff() {
    GameStateDiff diff = new GameStateDiff();
    diff.roomId = roomId;
    diff.seq = tickCounter;

    // 食物变化
    if (!food.equals(previousFood)) {
      diff.food = new Position(food.x, food.y);
    }

    // 玩家差分
    for (Map.Entry<String, Player> entry : players.entrySet()) {
      String username = entry.getKey();
      Player player = entry.getValue();

      List<Position> prevBody = previousPlayerBodies.get(username);
      if (prevBody == null) {
        // 新加入，放入新玩家列表（全量快照由上层另外发送，这里只标记）
        // 注意：实际发送新玩家全量由GameActor在JOIN时单独发送，此处newPlayers可选
        // 但为了完整性，仍然可以填充，上层可忽略或使用
        diff.newPlayers.add(snapshotPlayerInfo(player));
        continue;
      }

      if (player.isDead) {
        diff.died.add(username);
        continue;
      }

      // 存活玩家差分
      PlayerDiff pd = new PlayerDiff();
      pd.newHead = player.body.get(0);

      // 判断尾巴是否移除：如果蛇变长了则不移除，否则移除
      boolean grew = (player.body.size() > prevBody.size());
      // 或者根据是否吃到食物判断
      pd.removeTail = !grew;
      pd.length = player.length;
      diff.players.put(username, pd);
    }

    // 已离开的玩家
    for (String oldName : previousPlayerNames) {
      if (!players.containsKey(oldName)) {
        diff.removedPlayers.add(oldName);
      }
    }

    return diff;
  }

  private GameStateData.PlayerInfo snapshotPlayerInfo(Player p) {
    GameStateData.PlayerInfo info = new GameStateData.PlayerInfo();
    info.name = p.username;
    info.head = p.body.get(0);
    info.body = p.body.toArray(new Position[0]);
    info.length = p.length;
    info.direction = p.direction;
    info.score = p.score;
    info.isDead = false;
    return info;
  }

  public boolean isEmpty() {
    return players.isEmpty();
  }

  public int getActivePlayers() {
    return activePlayers;
  }

  public List<Player> getPlayers() {
    return new ArrayList<>(players.values());
  }

  // --- 以下为从快照恢复的构造函数（略作调整，不影响差分逻辑）---
  public GameState(int roomId, GameStateData snapshot) {
    this.roomId = roomId;
    this.food = snapshot.food;
    this.activePlayers = snapshot.activePlayers;
    this.totalPlayers = snapshot.totalPlayers;
    for (int y = 0; y < Config.MAP_HEIGHT; y++) {
      for (int x = 0; x < Config.MAP_WIDTH; x++) map[y][x] = ' ';
    }
    for (int x = 0; x < Config.MAP_WIDTH; x++) {
      map[0][x] = '#';
      map[Config.MAP_HEIGHT - 1][x] = '#';
    }
    for (int y = 0; y < Config.MAP_HEIGHT; y++) {
      map[y][0] = '#';
      map[y][Config.MAP_WIDTH - 1] = '#';
    }
    for (int i = 0; i < snapshot.obstacleCount; i++) {
      obstacles[i] = snapshot.obstacles[i];
      map[obstacles[i].y][obstacles[i].x] = 'X';
    }
    map[food.y][food.x] = 'F';
    for (int i = 0; i < snapshot.playerCount; i++) {
      GameStateData.PlayerInfo pi = snapshot.players[i];
      Player p = new Player();
      p.username = pi.name;
      p.body = new ArrayList<>(Arrays.asList(pi.body).subList(0, pi.length));
      p.length = pi.length;
      p.direction = pi.direction;
      p.score = pi.score;
      p.isDead = pi.isDead;
      players.put(p.username, p);
    }
    this.initialDelayDone = true;
    // 初始化差分历史
    previousFood = new Position(food.x, food.y);
    previousPlayerNames = new HashSet<>(players.keySet());
    for (Player p : players.values()) {
      previousPlayerBodies.put(p.username, new ArrayList<>(p.body));
    }
  }

  public static class Player {
    public String username;
    public List<Position> body;
    public int length;
    public Direction direction;
    public int score;
    public boolean isDead;
  }
}
