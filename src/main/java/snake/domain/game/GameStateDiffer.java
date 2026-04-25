package snake.domain.game;

import java.util.*;
import snake.common.Position;

/** 负责记录 GameState 的“前一帧”快照，并在需要时比较当前帧生成差分。 由 GameTickProcessor 在 tick 前后调用。 */
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
    diff.roomId = state.snapshot(null).roomId; // 简单地获取 roomId，也可从 state 直接引入
    // 实际 roomId 可以从外部传入，这里简单通过快照
    // 更好的方式：GameState 暴露 getRoomId()，我们使用 state 内部属性（但 state 不暴露 roomId，可加一个 getter）
    // 为了简洁，这里用一个临时变量：实际 GameState 应该有个 getRoomId()，在下方修正
    diff.roomId = getRoomId(); // 调用下面的私有方法

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
      // 简单判断尾巴是否移除：如果蛇身长度增加，不移除尾巴
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

  // 临时方法，理想情况应在 GameState 中添加 getRoomId()
  private int getRoomId() {
    // 通过快照获取 roomId（不够优雅，但可工作）
    return state.snapshot(null).roomId;
  }
}
