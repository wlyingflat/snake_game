package snake.ecs.components;

import snake.ecs.Component;

public class PlayerOwnerComponent implements Component {
  public String username;

  public PlayerOwnerComponent(String username) {
    this.username = username;
  }
}
