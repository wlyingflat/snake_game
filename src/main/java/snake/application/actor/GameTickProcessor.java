package snake.application.actor;

import java.util.*;
import snake.common.*;
import snake.domain.game.GameState;
import snake.domain.game.GameStateDiff;
import snake.domain.game.GameStateDiffer;

/** 负责游戏状态的一次更新（tick），包括： - 状态推进 - 分数事件和死亡事件发布 - 差分或全量广播策略 不处理客户端消息，不管理线程。 */
public class GameTickProcessor {
  private final int roomId;
  private final GameState state;
  private final GameStateDiffer differ;
  private final ActorNotifier notifier;
  private final Runnable onStatusChange;
  private final ILogger logger = Logger.getInstance();

  private GameStateData cachedSnapshot;
  private int tickSinceLastFullState = 0;
  private static final int FULL_STATE_INTERVAL_TICKS = 100;

  public GameTickProcessor(
      int roomId, GameState state, ActorNotifier notifier, Runnable onStatusChange) {
    this.roomId = roomId;
    this.state = state;
    this.notifier = notifier;
    this.onStatusChange = onStatusChange;
    this.differ = new GameStateDiffer(state); // 创建差分器
    // 在游戏开始时立即捕获一次基线
    differ.captureBeforeTick();
    updateCachedSnapshot();
  }

  public void processTick() {
    // 如果房间为空，不推进逻辑（但由外部检查空闲）
    if (state.isEmpty()) return;

    // 记录旧分数和存活状态
    Map<String, Integer> oldScores = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      oldScores.put(p.username, p.score);
    }
    Map<String, Boolean> wasAlive = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      wasAlive.put(p.username, !p.isDead);
    }

    differ.captureBeforeTick();
    // 执行状态更新
    state.update();

    // 分数变化事件
    for (GameState.Player p : state.getPlayers()) {
      int oldScore = oldScores.getOrDefault(p.username, 0);
      if (p.score > oldScore) {
        notifier.publishScoreChanged(p.username, roomId, p.score, p.score - oldScore);
      }
    }

    // 死亡检测
    List<String> diedPlayers = new ArrayList<>();
    for (GameState.Player p : state.getPlayers()) {
      Boolean aliveBefore = wasAlive.get(p.username);
      if (aliveBefore != null && aliveBefore && p.isDead) {
        diedPlayers.add(p.username);
      }
    }
    for (String username : diedPlayers) {
      GameState.Player player =
          state.getPlayers().stream()
              .filter(p -> p.username.equals(username))
              .findFirst()
              .orElse(null);
      if (player != null) {
        notifier.publishPlayerDied(username, roomId, player.score, player.length, "COLLISION");
        state.removePlayer(username);
        notifier.sendToPlayer(username, null, "{\"cmd\":\"YOU_DIED\"}");
      }
    }

    // 广播策略
    tickSinceLastFullState++;
    boolean forceFull =
        (tickSinceLastFullState >= FULL_STATE_INTERVAL_TICKS) || state.hasNewPlayer();
    if (forceFull) {
      updateCachedSnapshotAndBroadcast();
      tickSinceLastFullState = 0;
    } else {
      GameStateDiff diff = differ.computeDiff();
      String json = new Serializer().serializeDiff(diff);
      if (json != null) {
        for (GameState.Player p : state.getPlayers()) {
          if (!p.isDead) {
            notifier.sendToPlayer(p.username, null, json);
          }
        }
      }
    }

    if (!diedPlayers.isEmpty() && onStatusChange != null) {
      onStatusChange.run();
    }
  }

  public void refreshCache() {
    this.cachedSnapshot = state.snapshot(null);
  }

  private void updateCachedSnapshotAndBroadcast() {
    cachedSnapshot = state.snapshot(null);
    if (cachedSnapshot == null) return;
    String json = new Serializer().serialize(cachedSnapshot);
    if (json == null) return;
    for (GameState.Player p : state.getPlayers()) {
      if (!p.isDead) {
        notifier.sendToPlayer(p.username, null, json);
      }
    }
  }

  private void updateCachedSnapshot() {
    cachedSnapshot = state.snapshot(null);
  }

  public GameStateData getCachedSnapshot() {
    return cachedSnapshot;
  }
}
