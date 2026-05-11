package snake.ecs.systems;

import java.util.*;
import snake.ecs.*;
import snake.ecs.components.*;

public class DeathOnSpikeSystem implements snake.ecs.System {
  @Override
  public void update(World world) {
    List<Entity> spikes = new ArrayList<>();
    List<Entity> balls = new ArrayList<>();
    for (Entity e : world.entities) {
      if (e.has(SpikeComponent.class)) spikes.add(e);
      else if (e.has(MassComponent.class)
          && !e.has(SpikeComponent.class)
          && e.has(PositionComponent.class)) balls.add(e);
    }

    Set<Entity> killed = new HashSet<>();
    for (Entity ball : balls) {
      PositionComponent bp = ball.get(PositionComponent.class);
      float ballR = (float) Math.sqrt(ball.get(MassComponent.class).mass) * 2;
      for (Entity spike : spikes) {
        PositionComponent sp = spike.get(PositionComponent.class);
        float spikeR = (float) Math.sqrt(spike.get(MassComponent.class).mass) * 2;
        float dx = bp.x - sp.x;
        float dy = bp.y - sp.y;
        if (dx * dx + dy * dy < (ballR + spikeR) * (ballR + spikeR)) {
          killed.add(ball);
          break;
        }
      }
    }
    for (Entity e : killed) {
      world.removeEntity(e);
    }
  }
}
