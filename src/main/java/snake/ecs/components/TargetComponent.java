package snake.ecs.components;

import snake.ecs.Component;

public class TargetComponent implements Component {
  public float tx, ty;

  public TargetComponent(float tx, float ty) {
    this.tx = tx;
    this.ty = ty;
  }
}
