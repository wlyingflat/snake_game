package snake.ecs.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import snake.common.*;
import snake.ecs.*;
import snake.ecs.components.*;

class CollisionSystemTest {
  private World world;
  private CollisionSystem collider;
  private List<Position> obstacles;

  @BeforeEach
  void setUp() {
    obstacles = new ArrayList<>();
    obstacles.add(new Position(20, 10));
    collider = new CollisionSystem(obstacles);
    world = new World();
  }

  private Entity createSnake(int headX, int headY, int length) {
    List<Position> body = new ArrayList<>();
    body.add(new Position(headX, headY));
    for (int i = 1; i < length; i++) {
      body.add(new Position(headX - i, headY));
    }
    Entity e = new Entity();
    e.add(new BodyComponent(body));
    e.add(new DirectionComponent(Direction.RIGHT)); // 方向无所谓
    e.add(new ScoreComponent(0, length));
    e.add(new AliveComponent());
    world.entities.add(e);
    return e;
  }

  @Test
  void wallCollisionKills() {
    // 蛇头直接放在右边界上
    Entity s = createSnake(Config.MAP_WIDTH - 1, 5, 3);
    collider.update(world);
    assertFalse(s.get(AliveComponent.class).alive);
  }

  @Test
  void obstacleCollisionKills() {
    // 蛇头直接放在障碍物上
    Entity s = createSnake(20, 10, 3);
    collider.update(world);
    assertFalse(s.get(AliveComponent.class).alive);
  }

  @Test
  void selfCollisionKills() {
    Entity s = createSnake(5, 5, 5);
    BodyComponent body = s.get(BodyComponent.class);
    body.segments.set(0, new Position(3, 5)); // 头碰到第4段
    collider.update(world);
    assertFalse(s.get(AliveComponent.class).alive);
  }

  @Test
  void twoSnakesCollideEachOther() {
    Entity s1 = createSnake(10, 10, 3);
    Entity s2 = createSnake(11, 10, 3);
    s1.get(BodyComponent.class).segments.set(0, new Position(10, 10));
    s2.get(BodyComponent.class).segments.set(0, new Position(10, 10));
    collider.update(world);
    assertFalse(s1.get(AliveComponent.class).alive);
    assertFalse(s2.get(AliveComponent.class).alive);
  }

  @Test
  void parallelStreamDoesNotThrow() {
    for (int i = 0; i < 64; i++) {
      createSnake(5 + i % 30, 5 + i / 30, 3);
    }
    assertDoesNotThrow(() -> collider.update(world));
  }
}
