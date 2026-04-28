// snake/ecs/systems/FoodRefreshSystem.java
package snake.ecs.systems;

import java.util.*;
import snake.common.Config;
import snake.common.Position;
import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;

public class FoodRefreshSystem implements System {
  private final List<Position> obstacles;
  private final Random rand = new Random();

  public FoodRefreshSystem(List<Position> obstacles) {
    this.obstacles = obstacles;
  }

  @Override
  public void update(World world) {
    Entity foodEntity =
        world.entities.stream().filter(e -> e.has(FoodComponent.class)).findFirst().orElse(null);
    if (foodEntity == null) return;

    FoodComponent food = foodEntity.get(FoodComponent.class);
    if (food.eaten) {
      Position newPos = null;
      int attempts = 0;
      do {
        newPos =
            new Position(
                rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
        attempts++;
        if (attempts > Config.MAX_SPAWN_ATTEMPTS) break;
      } while (isOccupied(newPos, world));

      if (newPos != null) {
        food.position = newPos;
      }
      food.eaten = false;
    }
  }

  private boolean isOccupied(Position pos, World world) {
    for (Position obs : obstacles) if (obs.equals(pos)) return true;
    return world.entities.stream()
        .filter(e -> e.has(BodyComponent.class))
        .flatMap(e -> e.get(BodyComponent.class).segments.stream())
        .anyMatch(seg -> seg.equals(pos));
  }
}
