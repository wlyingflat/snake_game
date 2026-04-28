// snake/ecs/systems/TailManagementSystem.java
package snake.ecs.systems;

import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;

public class TailManagementSystem implements System {
  @Override
  public void update(World world) {
    Entity food =
        world.entities.stream().filter(e -> e.has(FoodComponent.class)).findFirst().orElse(null);
    FoodComponent fc = food.get(FoodComponent.class);

    for (Entity e : world.entities) {
      if (!e.has(AliveComponent.class) || !e.get(AliveComponent.class).alive) continue;
      BodyComponent body = e.get(BodyComponent.class);
      ScoreComponent score = e.get(ScoreComponent.class);
      boolean ate = fc != null && body.segments.get(0).equals(fc.position);
      if (ate) {
        score.score++;
        score.length = body.segments.size();
        fc.eaten = true;
      } else {
        body.segments.remove(body.segments.size() - 1);
        score.length = body.segments.size();
      }
    }
  }
}
