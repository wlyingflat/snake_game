// snake/ecs/Entity.java
package snake.ecs;

import java.util.HashMap;
import java.util.Map;

public class Entity {
  private final Map<Class<?>, Component> components = new HashMap<>();

  public <T extends Component> T get(Class<T> type) {
    return type.cast(components.get(type));
  }

  public <T extends Component> void add(T component) {
    components.put(component.getClass(), component);
  }

  public void remove(Class<?> type) {
    components.remove(type);
  }

  public boolean has(Class<?> type) {
    return components.containsKey(type);
  }
}
