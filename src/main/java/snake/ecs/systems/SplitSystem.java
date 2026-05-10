package snake.ecs.systems;

import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.*;

public class SplitSystem implements System {
  public static final float SPLIT_MASS_RATIO = 0.5f;
  public static final long SPLIT_COOLDOWN = 500;
  public static final long MERGE_LOCK_TIME = 10000;

  @Override
  public void update(World world) {
    for (Entity e : world.entities) {
      if (e.has(MergeLockComponent.class)) {
        MergeLockComponent lock = e.get(MergeLockComponent.class);
        if (java.lang.System.currentTimeMillis() > lock.lockUntil) {
          e.remove(MergeLockComponent.class);
        }
      }
    }
  }

  public static void executeSplit(World world, Entity parent, float mx, float my) {
    MassComponent pm = parent.get(MassComponent.class);
    if (pm.mass < 36) return;
    SplitCooldownComponent cd = parent.get(SplitCooldownComponent.class);
    if (cd != null && java.lang.System.currentTimeMillis() - cd.lastSplitTime < SPLIT_COOLDOWN)
      return;

    float newMass = pm.mass * SPLIT_MASS_RATIO;
    pm.mass -= newMass;

    PositionComponent pp = parent.get(PositionComponent.class);
    float angle = (float) Math.atan2(my - pp.y, mx - pp.x);
    float initDist = (float) (Math.sqrt(pm.mass) * 2 + Math.sqrt(newMass) * 2 + 10);

    Entity child = world.createEntity();
    child.add(new MassComponent(newMass));
    child.add(
        new PositionComponent(
            pp.x + (float) Math.cos(angle) * initDist, pp.y + (float) Math.sin(angle) * initDist));
    child.add(new VelocityComponent(0, 0));
    child.add(new TargetComponent(mx, my));
    child.add(
        new MergeLockComponent(java.lang.System.currentTimeMillis() + MERGE_LOCK_TIME, parent));

    if (cd != null) cd.lastSplitTime = java.lang.System.currentTimeMillis();
  }
}
