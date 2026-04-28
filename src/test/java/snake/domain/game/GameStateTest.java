package snake.domain.game;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import snake.common.Direction;

class GameStateTest {
  private GameState state;

  @BeforeEach
  void setUp() {
    state = new GameState(1);
  }

  @Test
  void addPlayerShouldIncreaseCount() {
    assertTrue(state.addPlayer("p1"));
    assertTrue(state.addPlayer("p2"));
    assertEquals(2, state.getActivePlayers());
    assertEquals(2, state.getPlayers().size());
  }

  @Test
  void addDuplicatePlayerShouldFail() {
    assertTrue(state.addPlayer("p1"));
    assertFalse(state.addPlayer("p1"));
  }

  @Test
  void removePlayerShouldDecreaseCount() {
    state.addPlayer("p1");
    state.addPlayer("p2");
    state.removePlayer("p1");
    assertEquals(1, state.getActivePlayers());
  }

  @Test
  void updateDirectionShouldChange() {
    state.addPlayer("p1");
    state.updateDirection("p1", Direction.UP);
    GameState.Player p = state.getPlayers().get(0);
    assertEquals(Direction.UP, p.direction);
  }

  @Test
  void emptyStateHasNewPlayer() {
    state.addPlayer("p1");
    assertTrue(state.hasNewPlayer());
    // 标记应被清除
    assertFalse(state.hasNewPlayer());
  }

  @Test
  void updateShouldNotThrow() {
    state.addPlayer("p1");
    for (int i = 0; i < 10; i++) {
      assertDoesNotThrow(() -> state.update());
    }
  }

  @Test
  void snapshotShouldContainValidData() {
    state.addPlayer("p1");
    state.update();
    var snapshot = state.snapshot(null);
    assertNotNull(snapshot);
    assertEquals(1, snapshot.roomId);
    assertNotNull(snapshot.food);
    assertEquals(15, snapshot.obstacleCount);
    assertEquals(1, snapshot.activePlayers);
  }
}
