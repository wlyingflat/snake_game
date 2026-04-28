// snake/ecs/World.java
package snake.ecs;

import java.util.ArrayList;
import java.util.List;

public class World {
  public final List<Entity> entities = new ArrayList<>();
  private final List<System> systems = new ArrayList<>();

  public void addSystem(System system) {
    systems.add(system);
  }

  public Entity createEntity() {
    Entity e = new Entity();
    entities.add(e);
    return e;
  }

  public void removeEntity(Entity e) {
    entities.remove(e);
  }

  public void update() {
    for (System system : systems) {
      system.update(this);
    }
  }
}
