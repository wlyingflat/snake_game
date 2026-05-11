package snake.ecs.components;

import snake.ecs.Component;

public class EjectRequestComponent implements Component {
  public float targetX, targetY;

  public EjectRequestComponent(float targetX, float targetY) {
    this.targetX = targetX;
    this.targetY = targetY;
  }
}
