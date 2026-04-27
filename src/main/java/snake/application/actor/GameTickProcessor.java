package snake.application.actor;

import java.util.*;
import snake.common.*;
import snake.domain.game.*;

public class GameTickProcessor {
  private final int roomId;
  private final GameState state;
  private final GameStateDiffer differ;
  private final ActorNotifier notifier;
  private final Runnable onStatusChange;
  private final ILogger logger = Logger.getInstance();
  private final FlatBuffersSerializer serializer = new FlatBuffersSerializer(); // 替代原 Serializer

  private GameStateData cachedSnapshot;
  private int tickSinceLastFullState = 0;
  private static final int FULL_STATE_INTERVAL_TICKS = 100;

  public GameTickProcessor(
      int roomId, GameState state, ActorNotifier notifier, Runnable onStatusChange) {
    this.roomId = roomId;
    this.state = state;
    this.notifier = notifier;
    this.onStatusChange = onStatusChange;
    this.differ = new GameStateDiffer(state);
    differ.captureBeforeTick();
    updateCachedSnapshot();
  }

  public void processTick() {
    if (state.isEmpty()) return;

    Map<String, Integer> oldScores = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      oldScores.put(p.username, p.score);
    }
    Map<String, Boolean> wasAlive = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      wasAlive.put(p.username, !p.isDead);
    }

    differ.captureBeforeTick();
    state.update();

    // 分数事件
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
        notifier.sendToPlayer(username, null, "{\"cmd\":\"YOU_DIED\"}"); // 文本提示
      }
    }

    tickSinceLastFullState++;
    boolean forceFull =
        (tickSinceLastFullState >= FULL_STATE_INTERVAL_TICKS) || state.hasNewPlayer();

    if (forceFull) {
      cachedSnapshot = state.snapshot(null);
      byte[] data = serializer.serializeGameState(cachedSnapshot);
      notifier.broadcastBinaryToRoom(state.getPlayers(), data, ActorNotifier.SUBTYPE_FULL_STATE);
      tickSinceLastFullState = 0;
    } else {
      GameStateDiff diff = differ.computeDiff();
      if (!diff.players.isEmpty() || !diff.died.isEmpty() || diff.food != null) {
        byte[] data = serializer.serializeDiff(diff);
        notifier.broadcastBinaryToRoom(state.getPlayers(), data, ActorNotifier.SUBTYPE_DIFF_STATE);
      }
    }

    if (!diedPlayers.isEmpty() && onStatusChange != null) {
      onStatusChange.run();
    }
  }

  public void refreshCache() {
    this.cachedSnapshot = state.snapshot(null);
  }

  private void updateCachedSnapshot() {
    cachedSnapshot = state.snapshot(null);
  }

  public GameStateData getCachedSnapshot() {
    return cachedSnapshot;
  }
}
