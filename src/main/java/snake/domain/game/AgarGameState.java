package snake.domain.game;

import java.util.*;
import snake.common.Position;
import snake.ecs.*;
import snake.ecs.components.*;
import snake.ecs.systems.*;

public class AgarGameState {
  private final World world;
  private final int mapWidth, mapHeight;
  private int totalPlayers = 0;

  // 仍保留玩家列表用于快速删除，但维护方式改为基于组件扫描
  private final Map<String, List<Entity>> playerEntities = new HashMap<>();

  public AgarGameState(int roomId, int mapWidth, int mapHeight) {
    this.mapWidth = mapWidth;
    this.mapHeight = mapHeight;
    this.world = new World();

    // 注册所有系统（顺序重要）
    world.addSystem(new MovementSystem(mapWidth, mapHeight));
    world.addSystem(new SplitExecutionSystem());
    world.addSystem(new EjectSystem());
    world.addSystem(new CollisionSystem());
    world.addSystem(new MergeUnlockSystem()); // 原 SplitSystem，处理合并冷却
    world.addSystem(new FoodSpawnSystem(mapWidth, mapHeight, 300));
    world.addSystem(new DeathOnSpikeSystem());

    Random rand = new Random();
    // 生成食物
    for (int i = 0; i < 200; i++) {
      Entity food = world.createEntity();
      food.add(new PositionComponent(rand.nextFloat() * mapWidth, rand.nextFloat() * mapHeight));
      food.add(new FoodComponent(10));
    }
    // 生成刺球
    for (int i = 0; i < 15; i++) {
      Entity spike = world.createEntity();
      spike.add(new PositionComponent(rand.nextFloat() * mapWidth, rand.nextFloat() * mapHeight));
      spike.add(new MassComponent(200 + rand.nextFloat() * 100));
      spike.add(new SpikeComponent());
    }
  }

  public boolean addPlayer(String username) {
    if (playerEntities.containsKey(username)) return false;
    if (totalPlayers >= 200) return false;

    List<Entity> entities = new ArrayList<>();
    Random rand = new Random();
    float startX = rand.nextFloat() * mapWidth;
    float startY = rand.nextFloat() * mapHeight;
    Entity mainBall = world.createEntity();
    mainBall.add(new MassComponent(100));
    mainBall.add(new PositionComponent(startX, startY));
    mainBall.add(new VelocityComponent(0, 0));
    mainBall.add(new TargetComponent(startX, startY));
    mainBall.add(new SplitCooldownComponent());
    mainBall.add(new PlayerOwnerComponent(username));
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

  // 移动目标
  public void updateTarget(String username, float x, float y) {
    List<Entity> balls = playerEntities.get(username);
    if (balls != null) {
      for (Entity ball : balls) {
        if (ball.has(TargetComponent.class)) {
          TargetComponent tc = ball.get(TargetComponent.class);
          tc.tx = x;
          tc.ty = y;
        }
      }
    }
  }

  // 请求分裂（仅添加组件，由 SplitExecutionSystem 处理）
  public void splitPlayer(String username, float targetX, float targetY) {
    List<Entity> balls = playerEntities.get(username);
    if (balls != null && !balls.isEmpty()) {
      Entity main = balls.get(0);
      main.add(new SplitRequestComponent(targetX, targetY));
      // 同步更新目标，让分裂出的球也朝向该方向
      if (main.has(TargetComponent.class)) {
        TargetComponent tc = main.get(TargetComponent.class);
        tc.tx = targetX;
        tc.ty = targetY;
      }
    }
  }

  // 请求弹射
  public void ejectMass(String username, float targetX, float targetY) {
    List<Entity> balls = playerEntities.get(username);
    if (balls != null && !balls.isEmpty()) {
      balls.get(0).add(new EjectRequestComponent(targetX, targetY));
    }
  }

  public void update() {
    world.update();
    syncPlayerEntities();
  }

  // 将 world 中的实体同步回玩家列表（自动补充分裂/弹射产生的新球，移除已删除的球）
  private void syncPlayerEntities() {
    // 先收集 world 中所有带 PlayerOwnerComponent 的实体，按玩家分组
    Map<String, List<Entity>> newMap = new HashMap<>();
    for (Entity e : world.entities) {
      if (e.has(PlayerOwnerComponent.class)) {
        String owner = e.get(PlayerOwnerComponent.class).username;
        newMap.computeIfAbsent(owner, k -> new ArrayList<>()).add(e);
      }
    }
    // 更新 playerEntities
    playerEntities.clear();
    playerEntities.putAll(newMap);
    totalPlayers = playerEntities.size();
  }

  public List<AgarPlayerState> getPlayerStates() {
    List<AgarPlayerState> states = new ArrayList<>();
    // 遍历所有有质量的实体（除了刺球）生成状态
    for (Entity e : world.entities) {
      if (!e.has(MassComponent.class) || !e.has(PositionComponent.class)) continue;
      if (e.has(SpikeComponent.class)) continue; // 刺球单独处理

      PositionComponent pos = e.get(PositionComponent.class);
      float mass = e.get(MassComponent.class).mass;
      String owner =
          e.has(PlayerOwnerComponent.class) ? e.get(PlayerOwnerComponent.class).username : "";
      states.add(new AgarPlayerState(owner, pos.x, pos.y, mass));
    }
    // 刺球
    for (Entity e : world.entities) {
      if (e.has(SpikeComponent.class)
          && e.has(PositionComponent.class)
          && e.has(MassComponent.class)) {
        PositionComponent pos = e.get(PositionComponent.class);
        states.add(new AgarPlayerState("SPIKE", pos.x, pos.y, e.get(MassComponent.class).mass));
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
}
