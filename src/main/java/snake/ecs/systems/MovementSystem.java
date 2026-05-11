package snake.ecs.systems;

import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;

public class MovementSystem implements System {
  private static final float DELTA = 0.2f;
  private final int mapWidth, mapHeight;

  // 从外部传入地图尺寸
  public MovementSystem(int mapWidth, int mapHeight) {
    this.mapWidth = mapWidth;
    this.mapHeight = mapHeight;
  }

  @Override
  public void update(World world) {
    for (Entity e : world.entities) {
      if (!e.has(PositionComponent.class)) continue;
      PositionComponent pos = e.get(PositionComponent.class);
      VelocityComponent vel = e.get(VelocityComponent.class);

      if (e.has(TargetComponent.class) && e.has(MassComponent.class)) {
        // 有目标的实体（玩家球）
        TargetComponent target = e.get(TargetComponent.class);
        MassComponent mass = e.get(MassComponent.class);
        float dx = target.tx - pos.x;
        float dy = target.ty - pos.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > 0.5f) {
          float speed = 300f / (float) Math.pow(mass.mass, 0.45f);
          vel.vx = dx / dist * speed;
          vel.vy = dy / dist * speed;
        } else {
          vel.vx = 0;
          vel.vy = 0;
        }
        pos.x += vel.vx * DELTA;
        pos.y += vel.vy * DELTA;
      } else if (vel != null) {
        // 惯性实体（弹出物等）
        pos.x += vel.vx * DELTA;
        pos.y += vel.vy * DELTA;
        // 摩擦
        vel.vx *= 0.98f;
        vel.vy *= 0.98f;
        // 边界反弹
        if (pos.x < 0) {
          pos.x = 0;
          vel.vx = -vel.vx;
        }
        if (pos.x > mapWidth) {
          pos.x = mapWidth;
          vel.vx = -vel.vx;
        }
        if (pos.y < 0) {
          pos.y = 0;
          vel.vy = -vel.vy;
        }
        if (pos.y > mapHeight) {
          pos.y = mapHeight;
          vel.vy = -vel.vy;
        }
      }
    }
  }
}
