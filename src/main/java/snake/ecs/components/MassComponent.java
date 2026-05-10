package snake.ecs.components;

import snake.ecs.Component;

public class MassComponent implements Component {
  public float mass;

  public MassComponent(float mass) {
    this.mass = mass;
  }
}
