package snake.ecs.systems;

import java.util.*;
import snake.ecs.*;
import snake.ecs.components.*;

public class EjectSystem implements snake.ecs.System {
  @Override
  public void update(World world) {
    List<Entity> ejectors = new ArrayList<>();
    for (Entity e : world.entities) {
      if (e.has(EjectRequestComponent.class)
          && e.has(MassComponent.class)
          && e.has(PositionComponent.class)) {
        ejectors.add(e);
      }
    }

    for (Entity e : ejectors) {
      EjectRequestComponent req = e.get(EjectRequestComponent.class);
      MassComponent pm = e.get(MassComponent.class);
      if (pm.mass < 30) {
        e.remove(EjectRequestComponent.class);
        continue;
      }

      float ejectMass = 16f;
      pm.mass -= ejectMass;

      PositionComponent pp = e.get(PositionComponent.class);
      float angle = (float) Math.atan2(req.targetY - pp.y, req.targetX - pp.x);
      float initDist = (float) (Math.sqrt(pm.mass) * 2 + 10);

      Entity ejected = world.createEntity();
      ejected.add(new MassComponent(ejectMass));
      ejected.add(
          new PositionComponent(
              pp.x + (float) Math.cos(angle) * initDist,
              pp.y + (float) Math.sin(angle) * initDist));
      ejected.add(new VelocityComponent((float) Math.cos(angle) * 4, (float) Math.sin(angle) * 4));
      // ❌ 移除这两行，弹出物不再属于玩家
      // if (e.has(PlayerOwnerComponent.class)) {
      //     ejected.add(new PlayerOwnerComponent(e.get(PlayerOwnerComponent.class).username));
      // }
      e.remove(EjectRequestComponent.class);
    }
  }
}
