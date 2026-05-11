package snake.ecs.systems;

import java.util.*;
import snake.ecs.*;
import snake.ecs.components.*;

public class SplitExecutionSystem implements snake.ecs.System {
  private static final long SPLIT_COOLDOWN = 500;
  private static final long MERGE_LOCK_TIME = 10000;

  @Override
  public void update(World world) {
    // 先收集所有需要分裂的实体
    List<Entity> splitters = new ArrayList<>();
    for (Entity e : world.entities) {
      if (e.has(SplitRequestComponent.class)
          && e.has(MassComponent.class)
          && e.has(PositionComponent.class)) {
        splitters.add(e);
      }
    }

    for (Entity parent : splitters) {
      SplitRequestComponent req = parent.get(SplitRequestComponent.class);
      SplitCooldownComponent cd = parent.get(SplitCooldownComponent.class);
      if (cd != null && java.lang.System.currentTimeMillis() - cd.lastSplitTime < SPLIT_COOLDOWN) {
        parent.remove(SplitRequestComponent.class);
        continue;
      }
      MassComponent mass = parent.get(MassComponent.class);
      if (mass.mass < 36) {
        parent.remove(SplitRequestComponent.class);
        continue;
      }

      float splitMass = mass.mass * 0.5f;
      mass.mass -= splitMass;

      PositionComponent pos = parent.get(PositionComponent.class);
      float angle = (float) Math.atan2(req.targetY - pos.y, req.targetX - pos.x);
      float initDist = (float) (Math.sqrt(mass.mass) * 2 + Math.sqrt(splitMass) * 2 + 10);

      // 安全创建子球
      Entity child = world.createEntity();
      child.add(new MassComponent(splitMass));
      child.add(
          new PositionComponent(
              pos.x + (float) Math.cos(angle) * initDist,
              pos.y + (float) Math.sin(angle) * initDist));
      child.add(new VelocityComponent(0, 0));
      child.add(new TargetComponent(req.targetX, req.targetY));
      child.add(new SplitCooldownComponent());
      child.add(
          new MergeLockComponent(java.lang.System.currentTimeMillis() + MERGE_LOCK_TIME, parent));

      if (parent.has(PlayerOwnerComponent.class)) {
        child.add(new PlayerOwnerComponent(parent.get(PlayerOwnerComponent.class).username));
      }

      if (cd != null) cd.lastSplitTime = java.lang.System.currentTimeMillis();
      parent.remove(SplitRequestComponent.class);
    }
  }
}
