// snake/ecs/components/ScoreComponent.java
package snake.ecs.components;

import snake.ecs.Component;

public class ScoreComponent implements Component {
  public int score;
  public int length;

  public ScoreComponent(int score, int length) {
    this.score = score;
    this.length = length;
  }
}
