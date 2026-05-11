package snake.ecs.components;

import snake.ecs.Component;

public class SplitRequestComponent implements Component {
  public float targetX, targetY;

  public SplitRequestComponent(float targetX, float targetY) {
    this.targetX = targetX;
    this.targetY = targetY;
  }
}
