// snake/ecs/systems/MovementSystem.java
package snake.ecs.systems;

import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;

public class MovementSystem implements System {
  // 修复：使用 tick 间隔（秒），默认200ms
  private static final float DELTA = 0.2f; // 将 0.016f 改为 0.2f

  @Override
  public void update(World world) {
    for (Entity e : world.entities) {
      if (!e.has(MassComponent.class) || !e.has(TargetComponent.class)) continue;
      MassComponent mass = e.get(MassComponent.class);
      TargetComponent target = e.get(TargetComponent.class);
      PositionComponent pos = e.get(PositionComponent.class);
      VelocityComponent vel = e.get(VelocityComponent.class);

      float dx = target.tx - pos.x;
      float dy = target.ty - pos.y;
      float dist = (float) Math.sqrt(dx * dx + dy * dy);
      if (dist < 0.5f) continue;

      float speed = 300f / (float) Math.pow(mass.mass, 0.45f);
      float vx = dx / dist * speed;
      float vy = dy / dist * speed;
      vel.vx = vx;
      vel.vy = vy;

      pos.x += vel.vx * DELTA; // 现在每步移动较大
      pos.y += vel.vy * DELTA;
    }
  }
}
