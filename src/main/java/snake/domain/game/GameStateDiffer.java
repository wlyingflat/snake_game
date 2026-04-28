// snake/domain/game/GameStateDiffer.java
package snake.domain.game;

import java.util.*;
import java.util.stream.Collectors;
import snake.common.GameStateData; // ★ 添加
import snake.common.Position;

public class GameStateDiffer {
  private final GameState state;
  private Position previousFood;
  private final Map<String, List<Position>> previousPlayerBodies = new HashMap<>();
  private Set<String> previousPlayerNames = new HashSet<>();

  public GameStateDiffer(GameState state) {
    this.state = state;
  }

  public void captureBeforeTick() {
    previousFood = new Position(state.getFood().x, state.getFood().y);
    previousPlayerBodies.clear();
    for (GameState.Player p : state.getPlayers()) {
      previousPlayerBodies.put(p.username, new ArrayList<>(p.body));
    }
    previousPlayerNames =
        state.getPlayers().stream().map(p -> p.username).collect(Collectors.toSet());
  }

  public GameStateDiff computeDiff() {
    GameStateDiff diff = new GameStateDiff();
    diff.roomId = state.getRoomId();

    Position currentFood = state.getFood();
    if (!currentFood.equals(previousFood)) {
      diff.food = new Position(currentFood.x, currentFood.y);
    }

    for (GameState.Player player : state.getPlayers()) {
      String username = player.username;
      List<Position> prevBody = previousPlayerBodies.get(username);
      if (prevBody == null) {
        diff.newPlayers.add(toPlayerInfo(player));
        continue;
      }
      if (player.isDead) {
        diff.died.add(username);
        continue;
      }
      PlayerDiff pd = new PlayerDiff();
      pd.newHead = player.body.get(0);
      pd.removeTail = (player.body.size() <= prevBody.size());
      pd.length = player.length;
      diff.players.put(username, pd);
    }

    for (String oldName : previousPlayerNames) {
      if (state.getPlayers().stream().noneMatch(p -> p.username.equals(oldName))) {
        diff.removedPlayers.add(oldName);
      }
    }
    return diff;
  }

  private GameStateData.PlayerInfo toPlayerInfo(GameState.Player p) {
    GameStateData.PlayerInfo info = new GameStateData.PlayerInfo();
    info.name = p.username;
    info.head = p.body.get(0);
    info.body = p.body.toArray(new Position[0]);
    info.length = p.length;
    info.direction = p.direction;
    info.score = p.score;
    info.isDead = false;
    return info;
  }
}
