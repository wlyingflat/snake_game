package snake.ecs.components;

import snake.ecs.Component;

public class FoodComponent implements Component {
  public float mass; // 食物质量
  public boolean eaten = false;

  public FoodComponent(float mass) {
    this.mass = mass;
  }
}
