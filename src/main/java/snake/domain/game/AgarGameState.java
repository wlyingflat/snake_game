package snake.domain.game;

import java.util.*;
import snake.common.Position;
import snake.ecs.*;
import snake.ecs.components.*;
import snake.ecs.systems.*;

public class AgarGameState {
  private final World world;
  private final Map<String, List<Entity>> playerEntities = new HashMap<>();
  private final int mapWidth, mapHeight;
  private int totalPlayers = 0;

  public AgarGameState(int roomId, int mapWidth, int mapHeight) {
    this.mapWidth = mapWidth;
    this.mapHeight = mapHeight;
    this.world = new World();
    world.addSystem(new MovementSystem());
    world.addSystem(new CollisionSystem());
    world.addSystem(new SplitSystem());
    world.addSystem(new FoodSpawnSystem(mapWidth, mapHeight, 300));

    Random rand = new Random();
    for (int i = 0; i < 200; i++) {
      Entity food = world.createEntity();
      food.add(new PositionComponent(rand.nextFloat() * mapWidth, rand.nextFloat() * mapHeight));
      food.add(new FoodComponent(10));
    }
  }

  public boolean addPlayer(String username) {
    if (playerEntities.containsKey(username)) return false;
    if (totalPlayers >= 200) return false;

    List<Entity> entities = new ArrayList<>();
    Random rand = new Random();
    float startX = rand.nextFloat() * mapWidth;
    float startY = rand.nextFloat() * mapHeight;
    Entity mainBall = createBall(startX, startY, 100);
    entities.add(mainBall);
    playerEntities.put(username, entities);
    totalPlayers++;
    return true;
  }

  public void removePlayer(String username) {
    List<Entity> balls = playerEntities.remove(username);
    if (balls != null) {
      for (Entity ball : balls) world.removeEntity(ball);
      totalPlayers = Math.max(0, totalPlayers - 1);
    }
  }

  private Entity createBall(float x, float y, float mass) {
    Entity ball = world.createEntity();
    ball.add(new MassComponent(mass));
    ball.add(new PositionComponent(x, y));
    ball.add(new VelocityComponent(0, 0));
    ball.add(new TargetComponent(x, y));
    ball.add(new SplitCooldownComponent());
    return ball;
  }

  public void updateTarget(String username, float x, float y) {
    List<Entity> balls = playerEntities.get(username);
    if (balls == null) return;
    x = clamp(x, 0, mapWidth);
    y = clamp(y, 0, mapHeight);
    for (Entity ball : balls) {
      if (ball.has(TargetComponent.class)) {
        TargetComponent tc = ball.get(TargetComponent.class);
        tc.tx = x;
        tc.ty = y;
      }
    }
  }

  public void splitPlayer(String username, float targetX, float targetY) {
    List<Entity> balls = playerEntities.get(username);
    if (balls == null || balls.isEmpty()) return;
    Entity mainBall = balls.get(0);
    SplitCooldownComponent cooldown = mainBall.get(SplitCooldownComponent.class);
    if (cooldown != null
        && java.lang.System.currentTimeMillis() - cooldown.lastSplitTime
            < SplitSystem.SPLIT_COOLDOWN) return;
    MassComponent mass = mainBall.get(MassComponent.class);
    if (mass.mass < 36) return;

    float splitMass = mass.mass * 0.5f;
    mass.mass -= splitMass;

    PositionComponent pos = mainBall.get(PositionComponent.class);
    float angle = (float) Math.atan2(targetY - pos.y, targetX - pos.x);
    float initDist = (float) (Math.sqrt(mass.mass) * 2 + Math.sqrt(splitMass) * 2 + 10);

    Entity child =
        createBall(
            pos.x + (float) Math.cos(angle) * initDist,
            pos.y + (float) Math.sin(angle) * initDist,
            splitMass);
    child.get(TargetComponent.class).tx = targetX;
    child.get(TargetComponent.class).ty = targetY;
    child.add(
        new MergeLockComponent(
            java.lang.System.currentTimeMillis() + SplitSystem.MERGE_LOCK_TIME, mainBall));
    balls.add(child);
    if (cooldown != null) cooldown.lastSplitTime = java.lang.System.currentTimeMillis();
  }

  public void ejectMass(String username, float targetX, float targetY) {
    List<Entity> balls = playerEntities.get(username);
    if (balls == null || balls.isEmpty()) return;
    Entity main = balls.get(0);
    MassComponent pm = main.get(MassComponent.class);
    if (pm.mass < 30) return;

    float ejectMass = 16f;
    pm.mass -= ejectMass;

    PositionComponent pp = main.get(PositionComponent.class);
    float angle = (float) Math.atan2(targetY - pp.y, targetX - pp.x);
    float initDist = (float) (Math.sqrt(pm.mass) * 2 + 10);

    Entity ejected = world.createEntity();
    ejected.add(new MassComponent(ejectMass));
    ejected.add(
        new PositionComponent(
            pp.x + (float) Math.cos(angle) * initDist, pp.y + (float) Math.sin(angle) * initDist));
    ejected.add(new VelocityComponent((float) Math.cos(angle) * 4, (float) Math.sin(angle) * 4));
  }

  public void update() {
    world.update();
    updateEjectedBalls();
    syncPlayerEntities();
  }

  private void updateEjectedBalls() {
    for (Entity e : world.entities) {
      if (!e.has(VelocityComponent.class) || !e.has(PositionComponent.class)) continue;
      if (e.has(TargetComponent.class)) continue; // 跳过玩家球
      PositionComponent pos = e.get(PositionComponent.class);
      VelocityComponent vel = e.get(VelocityComponent.class);
      pos.x += vel.vx * 0.016f;
      pos.y += vel.vy * 0.016f;
      if (pos.x < 0 || pos.x > mapWidth) vel.vx = -vel.vx;
      if (pos.y < 0 || pos.y > mapHeight) vel.vy = -vel.vy;
      pos.x = clamp(pos.x, 0, mapWidth);
      pos.y = clamp(pos.y, 0, mapHeight);
    }
  }

  private void syncPlayerEntities() {
    Iterator<Map.Entry<String, List<Entity>>> it = playerEntities.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, List<Entity>> entry = it.next();
      entry.getValue().removeIf(ball -> !world.entities.contains(ball));
      if (entry.getValue().isEmpty()) {
        it.remove();
        totalPlayers--;
      }
    }
  }

  // 修正后的 getPlayerStates：返回玩家球 + 中立球（弹出质量）
  public List<AgarPlayerState> getPlayerStates() {
    List<AgarPlayerState> states = new ArrayList<>();
    // 先收集所有特定玩家的实体，用于排除
    Set<Entity> ownedEntities = new HashSet<>();
    for (List<Entity> list : playerEntities.values()) {
      ownedEntities.addAll(list);
    }

    // 1. 添加玩家控制的球
    for (Map.Entry<String, List<Entity>> e : playerEntities.entrySet()) {
      String username = e.getKey();
      for (Entity ball : e.getValue()) {
        if (ball.has(MassComponent.class) && ball.has(PositionComponent.class)) {
          PositionComponent pos = ball.get(PositionComponent.class);
          states.add(
              new AgarPlayerState(username, pos.x, pos.y, ball.get(MassComponent.class).mass));
        }
      }
    }

    // 2. 添加弹出的质量球（中立球，不属于任何玩家）
    for (Entity entity : world.entities) {
      if (!ownedEntities.contains(entity)
          && entity.has(MassComponent.class)
          && entity.has(PositionComponent.class)) {
        // 排除食物（有FoodComponent的实体）
        if (entity.has(FoodComponent.class)) continue;
        PositionComponent pos = entity.get(PositionComponent.class);
        float mass = entity.get(MassComponent.class).mass;
        // 没有名字，客户端可以渲染为无色球或灰色
        states.add(new AgarPlayerState("", pos.x, pos.y, mass));
      }
    }
    return states;
  }

  public List<Position> getFoodPositions() {
    List<Position> list = new ArrayList<>();
    for (Entity e : world.entities) {
      if (e.has(FoodComponent.class) && !e.get(FoodComponent.class).eaten) {
        PositionComponent pc = e.get(PositionComponent.class);
        list.add(new Position((int) pc.x, (int) pc.y));
      }
    }
    return list;
  }

  // 为了兼容老代码，这个 getActiveUsernames 暂时保留，返回玩家用户名列表
  public Iterable<GameState.Player> getActiveUsernames() {
    List<GameState.Player> list = new ArrayList<>();
    for (String username : playerEntities.keySet()) {
      GameState.Player p = new GameState.Player();
      p.username = username;
      p.isDead = false;
      list.add(p);
    }
    return list;
  }

  public boolean isEmpty() {
    return playerEntities.isEmpty();
  }

  public int getActivePlayers() {
    return playerEntities.size();
  }

  public static class AgarPlayerState {
    public String username;
    public float x, y, mass;

    public AgarPlayerState(String username, float x, float y, float mass) {
      this.username = username;
      this.x = x;
      this.y = y;
      this.mass = mass;
    }
  }

  private float clamp(float val, float min, float max) {
    return Math.max(min, Math.min(max, val));
  }
}
