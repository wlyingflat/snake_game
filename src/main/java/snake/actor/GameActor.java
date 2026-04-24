package snake.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.base.*;
import snake.distributed.DistributedCoordinator;
import snake.event.KafkaEventProducer;
import snake.game.event.*;
import snake.game.state.GameState;
import snake.mq.MessageBus;
import snake.network.Serializer;

public class GameActor {
  private final int roomId;
  private final String workerId;
  private final Disruptor<MessageEvent> disruptor;
  private final RingBuffer<MessageEvent> ringBuffer;
  private final GameState state;
  private final ActorNotifier notifier;
  private final DistributedCoordinator coordinator;
  private final Runnable onStatusChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ScheduledExecutorService tickScheduler;
  private final ScheduledExecutorService idleCheckScheduler;
  private volatile GameStateData cachedSnapshot;
  private long lastActiveTime;
  private final ILogger logger = Logger.getInstance();

  // 定期全量纠正间隔（tick 次数）
  private int tickSinceLastFullState = 0;
  private static final int FULL_STATE_INTERVAL_TICKS = 100; // 可根据 Config 调整

  public GameActor(
      int roomId,
      DistributedCoordinator coordinator,
      String workerId,
      KafkaEventProducer eventProducer,
      MessageBus messageBus,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.coordinator = coordinator;
    this.workerId = workerId;
    this.onStatusChange = onStatusChange;
    this.state = new GameState(roomId);
    this.notifier = new ActorNotifier(coordinator, eventProducer, messageBus);
    this.lastActiveTime = System.currentTimeMillis();

    this.disruptor =
        new Disruptor<>(
            MessageEvent.FACTORY,
            Config.RING_BUFFER_SIZE,
            r -> {
              Thread t = new Thread(r);
              t.setName("actor-" + roomId);
              t.setDaemon(true);
              return t;
            },
            ProducerType.MULTI,
            new YieldingWaitStrategy());

    this.disruptor.handleEventsWith(
        (event, sequence, endOfBatch) -> {
          Message msg = event.getMessage();
          if (msg instanceof TickMessage) {
            doGameTick();
          } else if (msg instanceof EnhancedMessage) {
            handleEnhancedMessage((EnhancedMessage) msg);
          }
          event.clear();
        });

    this.ringBuffer = disruptor.getRingBuffer();
    disruptor.start();

    this.tickScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("actor-" + roomId + "-tick");
              t.setDaemon(true);
              return t;
            });
    tickScheduler.scheduleAtFixedRate(
        () -> {
          if (running.get()) publishEvent(new TickMessage());
        },
        0,
        Config.TICK_INTERVAL_MS,
        TimeUnit.MILLISECONDS);

    this.idleCheckScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("actor-" + roomId + "-idle");
              t.setDaemon(true);
              return t;
            });
    idleCheckScheduler.scheduleAtFixedRate(
        this::checkIdleAndStop,
        Config.ROOM_IDLE_TIMEOUT,
        Config.ROOM_IDLE_TIMEOUT,
        TimeUnit.SECONDS);

    updateCachedSnapshot();
    logger.info("Actor " + roomId + " started on worker " + this.workerId);
  }

  // ==================== 外部接口 ====================
  public void postMessage(EnhancedMessage msg) {
    if (!running.get()) return;
    publishEvent(msg);
  }

  // public void post(Message msg) {
  //   if (!running.get()) return;
  //   publishEvent(msg);
  // }

  public int getRoomId() {
    return roomId;
  }

  public boolean isRunning() {
    return running.get();
  }

  public GameStateData getSnapshot(String username) {
    return cachedSnapshot;
  }

  // ==================== 消息处理 ====================
  private void handleEnhancedMessage(EnhancedMessage msg) {
    switch (msg.getCommand()) {
      case "CREATE":
      case "JOIN":
        handleJoin(msg.getUsername(), msg.getGatewayId());
        break;
      case "INPUT":
        handleInput(msg);
        break;
      case "LEAVE":
        handleLeave(msg.getUsername(), msg.getGatewayId());
        break;
      default:
        logger.warn("Actor " + roomId + " unknown command: " + msg.getCommand());
    }
  }

  private void handleJoin(String username, String gatewayId) {
    boolean joined = state.addPlayer(username);
    if (joined) {
      String joinOk = buildJoinOkMessage();
      notifier.sendToPlayer(username, gatewayId, joinOk);
      if (gatewayId != null) {
        coordinator.setPlayerLocation(username, gatewayId, roomId);
      }
      // 向新玩家发送全量快照
      sendFullStateTo(username, gatewayId);
      logger.info("Player " + username + " joined actor " + roomId);
      if (onStatusChange != null) onStatusChange.run();
    } else {
      String joinFail = buildJoinFailMessage();
      notifier.sendToPlayer(username, gatewayId, joinFail);
      logger.warn("Player " + username + " failed to join actor " + roomId);
    }
    // 加入后也需要更新其他玩家的差分/全量，由下一 tick 处理
  }

  private void handleInput(EnhancedMessage msg) {
    try {
      JsonNode params = JsonUtils.MAPPER.readTree(msg.getRawMessage());
      Direction dir = Direction.valueOf(params.get("direction").asText());
      state.updateDirection(msg.getUsername(), dir);
    } catch (Exception e) {
      logger.error("Failed to parse input: " + e.getMessage());
    }
  }

  private void handleLeave(String username, String gatewayId) {
    state.removePlayer(username);
    String leaveMsg = "{\"cmd\":\"YOU_LEFT\"}";
    notifier.sendToPlayer(username, gatewayId, leaveMsg);
    coordinator.removePlayerLocation(username);
    logger.info("Player " + username + " left actor " + roomId);
    if (onStatusChange != null) onStatusChange.run();
    updateCachedSnapshotAndBroadcast();
  }

  // ==================== 游戏逻辑 ====================
  private void doGameTick() {
    if (!state.isEmpty()) {
      lastActiveTime = System.currentTimeMillis();
    }

    Map<String, Integer> oldScores = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      oldScores.put(p.username, p.score);
    }

    Map<String, Boolean> wasAlive = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      wasAlive.put(p.username, !p.isDead);
    }

    state.update();

    for (GameState.Player p : state.getPlayers()) {
      int oldScore = oldScores.getOrDefault(p.username, 0);
      if (p.score > oldScore) {
        notifier.publishScoreChanged(p.username, roomId, p.score, p.score - oldScore);
      }
    }

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
      }
      state.removePlayer(username);
      notifier.sendToPlayer(username, null, "{\"cmd\":\"YOU_DIED\"}");
    }

    // 决定发送全量还是增量
    tickSinceLastFullState++;
    boolean forceFull =
        (tickSinceLastFullState >= FULL_STATE_INTERVAL_TICKS) || state.hasNewPlayer();
    if (forceFull) {
      updateCachedSnapshotAndBroadcast();
      tickSinceLastFullState = 0;
    } else {
      broadcastDiff();
    }

    if (!diedPlayers.isEmpty() && onStatusChange != null) {
      onStatusChange.run();
    }
  }

  /** 全量广播 - 原有逻辑 */
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

  /** 只更新缓存的快照（不广播） */
  private void updateCachedSnapshot() {
    cachedSnapshot = state.snapshot(null);
  }

  /** 增量广播 */
  private void broadcastDiff() {
    GameStateDiff diff = state.computeDiff();
    if (diff == null) return;
    String json = new Serializer().serializeDiff(diff);
    if (json == null) return;
    for (GameState.Player p : state.getPlayers()) {
      if (!p.isDead) {
        notifier.sendToPlayer(p.username, null, json);
      }
    }
  }

  /** 向指定玩家发送全量快照（用于新玩家加入） */
  private void sendFullStateTo(String username, String gatewayId) {
    if (cachedSnapshot == null) {
      cachedSnapshot = state.snapshot(username);
    }
    String json = new Serializer().serialize(cachedSnapshot);
    if (json != null) {
      notifier.sendToPlayer(username, gatewayId, json);
    }
  }

  private void checkIdleAndStop() {
    if (!running.get()) return;
    if (state.isEmpty()) {
      long idleMillis = System.currentTimeMillis() - lastActiveTime;
      if (idleMillis > Config.ROOM_IDLE_TIMEOUT * 1000L) {
        logger.info("Actor " + roomId + " idle timeout, stopping.");
        stop();
      }
    }
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) return;
    logger.info("Actor " + roomId + " is stopping...");
    for (GameState.Player p : state.getPlayers()) {
      notifier.sendToPlayer(p.username, null, "{\"cmd\":\"ROOM_CLOSED\"}");
      coordinator.removePlayerLocation(p.username);
    }
    if (onStatusChange != null) {
      try {
        onStatusChange.run();
      } catch (Exception e) {
      }
    }
    tickScheduler.shutdown();
    idleCheckScheduler.shutdown();
    try {
      tickScheduler.awaitTermination(5, TimeUnit.SECONDS);
      idleCheckScheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    disruptor.shutdown();
    logger.info("Actor " + roomId + " destroyed.");
  }

  // ==================== 辅助方法 ====================
  private void publishEvent(Message msg) {
    try {
      long sequence = ringBuffer.tryNext();
      MessageEvent event = ringBuffer.get(sequence);
      event.setMessage(msg);
      ringBuffer.publish(sequence);
    } catch (InsufficientCapacityException e) {
      logger.warn("Actor " + roomId + " ring buffer full");
    }
  }

  private String buildJoinOkMessage() {
    ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "JOIN_OK");
    resp.put("roomId", roomId);
    try {
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String buildJoinFailMessage() {
    try {
      ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
      resp.put("cmd", "JOIN_FAIL");
      resp.put("message", "Room is full or join failed");
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{}";
    }
  }
}
