// snake/domain/game/GameState.java
package snake.domain.game;

import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import snake.common.*;
import snake.ecs.Entity;
import snake.ecs.World;
import snake.ecs.components.*;
import snake.ecs.systems.*;

public class GameState {
  private final int roomId;
  private final World world;
  private final List<Position> obstacles;
  private final Position[] obstaclesArray = new Position[Config.OBSTACLE_COUNT];
  private final Random rand = new Random();
  private boolean initialDelayDone = false;

  // 使用 AtomicIntegerFieldUpdater 替代 volatile 直接写
  private static final AtomicIntegerFieldUpdater<GameState> ACTIVE_PLAYERS_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(GameState.class, "activePlayers");
  private static final AtomicIntegerFieldUpdater<GameState> TOTAL_PLAYERS_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(GameState.class, "totalPlayers");
  private static final AtomicIntegerFieldUpdater<GameState> TICK_COUNTER_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(GameState.class, "tickCounter");

  private volatile int tickCounter = 0;
  private volatile int totalPlayers = 0;
  private volatile int activePlayers = 0;

  private final Set<String> newPlayersThisTick = new HashSet<>();
  private final Map<String, Entity> playerEntities = new HashMap<>();

  public GameState(int roomId) {
    this.roomId = roomId;
    this.obstacles = new ArrayList<>();
    this.world = new World();
    world.addSystem(new MovementSystem());
    world.addSystem(new CollisionSystem(obstacles));
    world.addSystem(new TailManagementSystem());
    world.addSystem(new FoodRefreshSystem(obstacles));
    initWorld();
  }

  public int getRoomId() {
    return roomId;
  }

  private void initWorld() {
    for (int i = 0; i < Config.OBSTACLE_COUNT; i++) {
      Position p;
      do {
        p =
            new Position(
                rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
        final Position finalP = p;
        if (obstacles.stream().noneMatch(o -> o.equals(finalP))) break;
      } while (true);
      obstacles.add(p);
      obstaclesArray[i] = p;
    }
    Entity foodEntity = world.createEntity();
    foodEntity.add(new FoodComponent(findSafeFoodPosition(null)));
  }

  private Position findSafeFoodPosition(Position exclude) {
    Position pos;
    int attempts = 0;
    do {
      pos =
          new Position(
              rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
      attempts++;
      final Position finalPos = pos;
      if (obstacles.stream().noneMatch(o -> o.equals(finalPos))
          && (exclude == null || !finalPos.equals(exclude))) break;
    } while (attempts <= Config.MAX_SPAWN_ATTEMPTS);
    return pos;
  }

  private Position findSafeSpawnPosition() {
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
      final Position finalPos = pos;
      if (!isOccupiedByObstacleOrSnake(finalPos)) break;
    } while (true);
    return pos;
  }

  private boolean isOccupiedByObstacleOrSnake(Position pos) {
    if (pos.x <= 0 || pos.x >= Config.MAP_WIDTH - 1 || pos.y <= 0 || pos.y >= Config.MAP_HEIGHT - 1)
      return true;
    for (Position obs : obstacles) if (obs.equals(pos)) return true;
    for (Entity e : playerEntities.values()) {
      if (!e.has(AliveComponent.class) || !e.get(AliveComponent.class).alive) continue;
      BodyComponent body = e.get(BodyComponent.class);
      for (Position seg : body.segments) if (seg.equals(pos)) return true;
    }
    return false;
  }

  public boolean addPlayer(String username) {
    if (playerEntities.containsKey(username) || totalPlayers >= Config.MAX_PLAYERS_PER_ROOM)
      return false;
    Position start = findSafeSpawnPosition();
    Entity entity = world.createEntity();
    List<Position> body = new ArrayList<>();
    body.add(start);
    entity.add(new BodyComponent(body));
    entity.add(new DirectionComponent(Direction.values()[rand.nextInt(4)]));
    entity.add(new ScoreComponent(0, 1));
    entity.add(new AliveComponent());
    playerEntities.put(username, entity);
    TOTAL_PLAYERS_UPDATER.incrementAndGet(this);
    ACTIVE_PLAYERS_UPDATER.incrementAndGet(this);
    initialDelayDone = true;
    newPlayersThisTick.add(username);
    return true;
  }

  public void removePlayer(String username) {
    Entity entity = playerEntities.remove(username);
    if (entity != null) {
      world.removeEntity(entity);
      TOTAL_PLAYERS_UPDATER.decrementAndGet(this);
      if (entity.has(AliveComponent.class) && entity.get(AliveComponent.class).alive)
        ACTIVE_PLAYERS_UPDATER.decrementAndGet(this);
    }
  }

  public void updateDirection(String username, Direction dir) {
    Entity e = playerEntities.get(username);
    if (e != null && e.has(AliveComponent.class) && e.get(AliveComponent.class).alive) {
      e.get(DirectionComponent.class).direction = dir;
    }
  }

  public void update() {
    if (!initialDelayDone) {
      int count = TICK_COUNTER_UPDATER.incrementAndGet(this);
      if (count >= Config.ROOM_INIT_DELAY_TICKS) initialDelayDone = true;
      return;
    }
    world.update();
    int alive = 0;
    for (Entity e : playerEntities.values()) {
      if (e.has(AliveComponent.class) && e.get(AliveComponent.class).alive) alive++;
    }
    ACTIVE_PLAYERS_UPDATER.set(this, alive);
    TICK_COUNTER_UPDATER.incrementAndGet(this);
  }

  public GameStateData snapshot(String clientUsername) {
    GameStateData data = new GameStateData();
    data.roomId = roomId;
    Entity foodEntity =
        world.entities.stream().filter(e -> e.has(FoodComponent.class)).findFirst().orElse(null);
    if (foodEntity != null) data.food = foodEntity.get(FoodComponent.class).position;
    data.obstacleCount = Config.OBSTACLE_COUNT;
    System.arraycopy(obstaclesArray, 0, data.obstacles, 0, Config.OBSTACLE_COUNT);
    List<Entity> players = new ArrayList<>(playerEntities.values());
    int count = Math.min(players.size(), Config.MAX_PLAYERS_PER_ROOM);
    data.playerCount = count;
    for (int i = 0; i < count; i++) {
      Entity e = players.get(i);
      data.players[i] = snapshotPlayerInfo(e);
    }
    data.activePlayers = activePlayers;
    data.totalPlayers = totalPlayers;
    return data;
  }

  private GameStateData.PlayerInfo snapshotPlayerInfo(Entity e) {
    GameStateData.PlayerInfo info = new GameStateData.PlayerInfo();
    BodyComponent body = e.get(BodyComponent.class);
    DirectionComponent dir = e.get(DirectionComponent.class);
    ScoreComponent score = e.get(ScoreComponent.class);
    info.name =
        playerEntities.entrySet().stream()
            .filter(entry -> entry.getValue() == e)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse("");
    info.head = body.segments.get(0);
    info.body = body.segments.toArray(new Position[0]);
    info.length = score.length;
    info.direction = dir.direction;
    info.score = score.score;
    info.isDead = !e.get(AliveComponent.class).alive;
    return info;
  }

  public boolean hasNewPlayer() {
    boolean res = !newPlayersThisTick.isEmpty();
    newPlayersThisTick.clear();
    return res;
  }

  public boolean isEmpty() {
    return playerEntities.isEmpty();
  }

  public int getActivePlayers() {
    return activePlayers;
  }

  public List<Player> getPlayers() {
    List<Player> result = new ArrayList<>();
    for (Map.Entry<String, Entity> entry : playerEntities.entrySet()) {
      Player p = new Player();
      p.username = entry.getKey();
      Entity e = entry.getValue();
      p.body = e.get(BodyComponent.class).segments;
      p.length = e.get(ScoreComponent.class).length;
      p.direction = e.get(DirectionComponent.class).direction;
      p.score = e.get(ScoreComponent.class).score;
      p.isDead = !e.get(AliveComponent.class).alive;
      result.add(p);
    }
    return result;
  }

  public Position getFood() {
    return world.entities.stream()
        .filter(e -> e.has(FoodComponent.class))
        .findFirst()
        .map(e -> e.get(FoodComponent.class).position)
        .orElse(null);
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
