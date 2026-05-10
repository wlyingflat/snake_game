package snake.ecs.systems;

import java.util.Random;
import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.FoodComponent;
import snake.ecs.components.PositionComponent;

public class FoodSpawnSystem implements System {
  private final Random rand = new Random();
  private final int mapWidth, mapHeight;
  private final int maxFood;
  private int tick = 0;

  public FoodSpawnSystem(int mapWidth, int mapHeight, int maxFood) {
    this.mapWidth = mapWidth;
    this.mapHeight = mapHeight;
    this.maxFood = maxFood;
  }

  @Override
  public void update(World world) {
    tick++;
    if (tick % 30 != 0) return; // 每30 tick生成一次

    long foodCount = world.entities.stream().filter(e -> e.has(FoodComponent.class)).count();
    int toSpawn = Math.min(5, maxFood - (int) foodCount);
    for (int i = 0; i < toSpawn; i++) {
      Entity food = world.createEntity();
      float x = rand.nextFloat() * mapWidth;
      float y = rand.nextFloat() * mapHeight;
      food.add(new PositionComponent(x, y));
      food.add(new FoodComponent(5 + rand.nextFloat() * 10));
    }
  }
}
