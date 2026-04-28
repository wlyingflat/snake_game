// snake/ecs/components/FoodComponent.java
package snake.ecs.components;

import snake.common.Position;
import snake.ecs.Component;

public class FoodComponent implements Component {
  public Position position;
  public boolean eaten = false;

  public FoodComponent(Position position) {
    this.position = position;
  }
}
