// snake/ecs/components/BodyComponent.java
package snake.ecs.components;

import java.util.List;
import snake.common.Position;
import snake.ecs.Component;

public class BodyComponent implements Component {
  public List<Position> segments; // 头在前

  public BodyComponent(List<Position> segments) {
    this.segments = segments;
  }
}
