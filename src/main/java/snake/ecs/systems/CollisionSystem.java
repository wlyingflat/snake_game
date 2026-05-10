// snake/ecs/systems/CollisionSystem.java
package snake.ecs.systems;

import java.util.*;
import java.util.stream.Collectors;
import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;

public class CollisionSystem implements System {

  @Override
  public void update(World world) {
    List<Entity> balls =
        world.entities.stream()
            .filter(
                e ->
                    e.has(MassComponent.class)
                        && !e.has(MergeLockComponent.class)
                        && e.has(PositionComponent.class))
            .collect(Collectors.toList());

    List<Entity> toRemove = new ArrayList<>();

    // ---------- 球与球的吞噬 ----------
    for (int i = 0; i < balls.size(); i++) {
      Entity a = balls.get(i);
      if (toRemove.contains(a)) continue;
      MassComponent am = a.get(MassComponent.class);
      PositionComponent ap = a.get(PositionComponent.class);

      for (int j = i + 1; j < balls.size(); j++) {
        Entity b = balls.get(j);
        if (toRemove.contains(b)) continue;
        MassComponent bm = b.get(MassComponent.class);
        PositionComponent bp = b.get(PositionComponent.class);

        float dx = ap.x - bp.x;
        float dy = ap.y - bp.y;
        float distSq = dx * dx + dy * dy;

        if (am.mass > bm.mass * 1.15f) {
          float bigR = (float) Math.sqrt(am.mass) * 2;
          if (distSq < bigR * bigR) {
            am.mass += bm.mass;
            toRemove.add(b);
            continue;
          }
        } else if (bm.mass > am.mass * 1.15f) {
          float bigR = (float) Math.sqrt(bm.mass) * 2;
          if (distSq < bigR * bigR) {
            bm.mass += am.mass;
            toRemove.add(a);
            break;
          }
        }
      }
    }

    // ---------- 球吃食物 ----------
    for (Entity ball : balls) {
      if (toRemove.contains(ball)) continue;
      MassComponent mass = ball.get(MassComponent.class);
      PositionComponent pos = ball.get(PositionComponent.class);
      float radius = (float) Math.sqrt(mass.mass) * 2; // 与吞噬半径一致

      // 遍历所有食物
      List<Entity> foods =
          world.entities.stream()
              .filter(e -> e.has(FoodComponent.class) && !e.get(FoodComponent.class).eaten)
              .toList();

      for (Entity food : foods) {
        FoodComponent fc = food.get(FoodComponent.class);
        PositionComponent fp = food.get(PositionComponent.class);
        float dx = pos.x - fp.x;
        float dy = pos.y - fp.y;
        if (dx * dx + dy * dy < radius * radius) {
          mass.mass += fc.mass; // 增加质量
          fc.eaten = true; // 标记食物被吃（FoodRefreshSystem 会刷新它）
        }
      }
    }

    for (Entity e : toRemove) {
      world.removeEntity(e);
    }
  }
}
