package snake.ecs.components;

import snake.ecs.Component;

public class VelocityComponent implements Component {
  public float vx, vy;

  public VelocityComponent(float vx, float vy) {
    this.vx = vx;
    this.vy = vy;
  }
}
