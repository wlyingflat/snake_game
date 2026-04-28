// snake/ecs/systems/CollisionSystem.java
package snake.ecs.systems;

import java.util.*;
import snake.common.Config;
import snake.common.Position;
import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;
// 用于障碍物引用

public class CollisionSystem implements System {
  private final List<Position> obstacles;
  private final int width = Config.MAP_WIDTH;
  private final int height = Config.MAP_HEIGHT;

  public CollisionSystem(List<Position> obstacles) {
    this.obstacles = obstacles;
  }

  @Override
  public void update(World world) {
    // 获取所有存活蛇实体
    List<Entity> snakes =
        world.entities.stream()
            .filter(e -> e.has(AliveComponent.class) && e.get(AliveComponent.class).alive)
            .toList();

    // 并行检测每条蛇的碰撞（只读，不修改实体）
    snakes.parallelStream()
        .forEach(
            e -> {
              BodyComponent body = e.get(BodyComponent.class);
              Position head = body.segments.get(0);

              // 边界碰撞
              if (head.x <= 0 || head.x >= width - 1 || head.y <= 0 || head.y >= height - 1) {
                e.get(AliveComponent.class).alive = false;
                return;
              }
              // 障碍物碰撞
              for (Position obs : obstacles) {
                if (obs != null && obs.x == head.x && obs.y == head.y) {
                  e.get(AliveComponent.class).alive = false;
                  return;
                }
              }
              // 自身碰撞
              for (int i = 1; i < body.segments.size(); i++) {
                if (head.equals(body.segments.get(i))) {
                  e.get(AliveComponent.class).alive = false;
                  return;
                }
              }
              // 与其他蛇碰撞
              for (Entity other : snakes) {
                if (other == e) continue;
                BodyComponent otherBody = other.get(BodyComponent.class);
                for (Position seg : otherBody.segments) {
                  if (head.equals(seg)) {
                    e.get(AliveComponent.class).alive = false;
                    return;
                  }
                }
              }
            });
  }
}
