// snake/ecs/systems/MovementSystem.java
package snake.ecs.systems;

import snake.common.Position;
import snake.ecs.Component;
import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.AliveComponent;
import snake.ecs.components.BodyComponent;
import snake.ecs.components.DirectionComponent;

public class MovementSystem implements System {
  @Override
  public void update(World world) {
    // 为每条活蛇计算新头并暂存
    world.entities.parallelStream()
        .filter(e -> e.has(AliveComponent.class) && e.get(AliveComponent.class).alive)
        .forEach(
            e -> {
              BodyComponent body = e.get(BodyComponent.class);
              DirectionComponent dir = e.get(DirectionComponent.class);
              Position head = body.segments.get(0);
              Position newHead =
                  switch (dir.direction) {
                    case UP -> new Position(head.x, head.y - 1);
                    case DOWN -> new Position(head.x, head.y + 1);
                    case LEFT -> new Position(head.x - 1, head.y);
                    case RIGHT -> new Position(head.x + 1, head.y);
                  };
              e.add(new NewHeadComponent(newHead));
            });

    // 插入新头（单线程，安全）
    for (Entity e : world.entities) {
      if (!e.has(NewHeadComponent.class)) continue;
      Position newHead = e.get(NewHeadComponent.class).position;
      e.get(BodyComponent.class).segments.add(0, newHead);
      e.remove(NewHeadComponent.class);
    }
  }

  private static class NewHeadComponent implements Component {
    final Position position;

    NewHeadComponent(Position p) {
      this.position = p;
    }
  }
}
