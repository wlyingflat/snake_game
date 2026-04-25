package snake.domain.game;

import java.util.*;
import snake.common.Position;

public class GameStateDiffer {

  private final GameState state;

  // 上一帧的快照
  private Position previousFood;
  private final Map<String, List<Position>> previousPlayerBodies = new HashMap<>();
  private Set<String> previousPlayerNames = new HashSet<>();

  public GameStateDiffer(GameState state) {
    this.state = state;
  }

  /** 在 state.update() 之前调用，记录当前状态作为“更新前”快照 */
  public void captureBeforeTick() {
    this.previousFood = new Position(state.getFood().x, state.getFood().y);

    previousPlayerBodies.clear();
    for (GameState.Player p : state.getPlayers()) {
      previousPlayerBodies.put(p.username, new ArrayList<>(p.body));
    }
    this.previousPlayerNames =
        new HashSet<>(
            state.getPlayers().stream()
                .map(p -> p.username)
                .collect(java.util.stream.Collectors.toSet()));
  }

  /** 在 state.update() 之后调用，生成 GameStateDiff */
  public GameStateDiff computeDiff() {
    GameStateDiff diff = new GameStateDiff();
    diff.roomId = state.getRoomId(); // 修复：直接使用 getter

    // 食物变化
    if (!state.getFood().equals(previousFood)) {
      diff.food = new Position(state.getFood().x, state.getFood().y);
    }

    // 玩家差分
    for (GameState.Player player : state.getPlayers()) {
      String username = player.username;

      List<Position> prevBody = previousPlayerBodies.get(username);
      if (prevBody == null) {
        // 新加入的玩家 → 放入 newPlayers
        diff.newPlayers.add(state.snapshotPlayerInfo(player));
        continue;
      }

      if (player.isDead) {
        diff.died.add(username);
        continue;
      }

      // 存活玩家，构造 PlayerDiff
      PlayerDiff pd = new PlayerDiff();
      pd.newHead = player.body.get(0);
      boolean grew = (player.body.size() > prevBody.size());
      pd.removeTail = !grew;
      pd.length = player.length;
      diff.players.put(username, pd);
    }

    // 已离开的玩家
    for (String oldName : previousPlayerNames) {
      if (!state.getPlayers().stream().anyMatch(p -> p.username.equals(oldName))) {
        diff.removedPlayers.add(oldName);
      }
    }

    return diff;
  }
}
