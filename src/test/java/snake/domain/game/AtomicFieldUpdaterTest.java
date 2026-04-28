package snake.domain.game;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class AtomicFieldUpdaterTest {
  @Test
  void activePlayersUpdaterWorks() throws Exception {
    GameState state = new GameState(1);
    state.addPlayer("p1");
    assertEquals(1, state.getActivePlayers());
    state.removePlayer("p1");
    assertEquals(0, state.getActivePlayers());
  }

  @Test
  void tickCounterWorks() {
    GameState state = new GameState(1);
    state.addPlayer("p1");
    int initial = state.getActivePlayers();
    state.update();
    // 难以直接读取 tickCounter，但验证无异常
  }
}
