package snake.ecs.components;

import snake.ecs.Component;

public class SplitCooldownComponent implements Component {
  public long lastSplitTime;

  public SplitCooldownComponent() {
    this.lastSplitTime = 0;
  }
}
