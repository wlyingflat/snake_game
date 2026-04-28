// snake/ecs/components/DirectionComponent.java
package snake.ecs.components;

import snake.common.Direction;
import snake.ecs.Component;

public class DirectionComponent implements Component {
  public Direction direction;

  public DirectionComponent(Direction direction) {
    this.direction = direction;
  }
}
