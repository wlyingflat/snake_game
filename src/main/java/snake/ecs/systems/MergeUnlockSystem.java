package snake.ecs.systems;

import snake.ecs.Entity;
import snake.ecs.System;
import snake.ecs.World;
import snake.ecs.components.MergeLockComponent;

public class MergeUnlockSystem implements System {
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
}
