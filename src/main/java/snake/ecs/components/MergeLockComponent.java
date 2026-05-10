package snake.ecs.components;

import snake.ecs.Component;
import snake.ecs.Entity;

public class MergeLockComponent implements Component {
  public long lockUntil;
  public Entity parent;

  public MergeLockComponent(long lockUntil, Entity parent) {
    this.lockUntil = lockUntil;
    this.parent = parent;
  }
}
