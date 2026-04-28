package snake.ecs.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import snake.common.*;
import snake.ecs.*;
import snake.ecs.components.*;

class ECSIntegrationTest {
  private World world;
  private List<Position> obstacles;

  @BeforeEach
  void setUp() {
    obstacles = new ArrayList<>();
    obstacles.add(new Position(5, 5));
    world = new World();

    world.addSystem(new MovementSystem());
    world.addSystem(new CollisionSystem(obstacles));
    world.addSystem(new TailManagementSystem());
    world.addSystem(new FoodRefreshSystem(obstacles));

    Entity food = new Entity();
    food.add(new FoodComponent(new Position(10, 10)));
    world.entities.add(food);
  }

  private Entity createSnake(int startX, int startY, int length, Direction dir) {
    List<Position> body = new ArrayList<>();
    body.add(new Position(startX, startY));
    for (int i = 1; i < length; i++) {
      body.add(new Position(startX - i, startY));
    }
    Entity e = new Entity();
    e.add(new BodyComponent(body));
    e.add(new DirectionComponent(dir));
    e.add(new ScoreComponent(0, length));
    e.add(new AliveComponent());
    world.entities.add(e);
    return e;
  }

  @Test
  void snakeMovesAndEatsFood() {
    Entity snake = createSnake(9, 10, 3, Direction.RIGHT);
    Entity foodEntity =
        world.entities.stream().filter(e -> e.has(FoodComponent.class)).findFirst().orElseThrow();
    FoodComponent food = foodEntity.get(FoodComponent.class);
    Position oldFoodPos = food.position;

    world.update();

    // 蛇头应该移动到 (10,10)，长度增加，食物位置改变
    assertEquals(new Position(10, 10), snake.get(BodyComponent.class).segments.get(0));
    assertEquals(4, snake.get(ScoreComponent.class).length);
    assertNotEquals(oldFoodPos, food.position);
  }

  @Test
  void snakeDiesOnWall() {
    Entity snake = createSnake(Config.MAP_WIDTH - 2, 10, 3, Direction.RIGHT);
    world.update();
    assertFalse(snake.get(AliveComponent.class).alive);
  }
}
