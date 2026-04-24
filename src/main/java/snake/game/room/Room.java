package snake.game.room;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.base.Config;
import snake.base.GameStateData;
import snake.base.ILogger;
import snake.base.JsonUtils;
import snake.base.Logger;
import snake.game.event.InputMsg;
import snake.game.event.JoinRoomMsg;
import snake.game.event.LeaveRoomMsg;
import snake.game.event.Message;
import snake.game.event.MessageEvent;
import snake.game.event.TickMessage;
import snake.game.notification.IGameClientNotifier;
import snake.game.state.GameState;
import snake.network.Serializer;

public class Room {
  private final int roomId;
  private final Disruptor<MessageEvent> disruptor;
  private final RingBuffer<MessageEvent> ringBuffer;
  private final GameState state;
  private final IGameClientNotifier notifier;
  private final IRoomDestroyCallback destroyCallback;
  private final Runnable onStatusChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ScheduledExecutorService tickScheduler; // 游戏 Tick 调度
  private final ScheduledExecutorService idleCheckScheduler; // 空闲检查调度
  private volatile GameStateData cachedSnapshot;
  private long lastActiveTime;
  private final ILogger logger = Logger.getInstance();

  public Room(
      int roomId,
      IGameClientNotifier notifier,
      IRoomDestroyCallback destroyCallback,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.notifier = notifier;
    this.destroyCallback = destroyCallback;
    logger.info(
        "Room "
            + roomId
            + " created, destroyCallback is "
            + (destroyCallback == null ? "NULL" : "set"));
    this.onStatusChange = onStatusChange;
    this.state = new GameState(roomId);
    this.lastActiveTime = System.currentTimeMillis();

    // 创建 Disruptor
    this.disruptor =
        new Disruptor<>(
            MessageEvent.FACTORY,
            Config.RING_BUFFER_SIZE,
            r -> {
              Thread t = new Thread(r);
              t.setName("room-" + roomId + "-disruptor");
              t.setDaemon(true);
              return t;
            },
            ProducerType.MULTI,
            new YieldingWaitStrategy());

    // 事件处理器
    this.disruptor.handleEventsWith(
        (event, sequence, endOfBatch) -> {
          Message msg = event.getMessage();
          if (msg instanceof TickMessage) {
            doGameTick();
          } else {
            handleMessage(msg);
          }
          event.clear();
        });

    this.ringBuffer = disruptor.getRingBuffer();
    disruptor.start();

    // 游戏 Tick 调度器（发布 TickMessage 到 Disruptor）
    this.tickScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("room-" + roomId + "-tick");
              t.setDaemon(true);
              return t;
            });
    tickScheduler.scheduleAtFixedRate(
        () -> {
          if (!running.get()) return;
          publishEvent(new TickMessage());
        },
        0,
        Config.TICK_INTERVAL_MS,
        TimeUnit.MILLISECONDS);

    // 独立空闲检查调度器（在独立线程中运行，避免 Disruptor 内调用 stop）
    this.idleCheckScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("room-" + roomId + "-idle-check");
              t.setDaemon(true);
              return t;
            });
    idleCheckScheduler.scheduleAtFixedRate(
        this::checkIdleAndStop,
        Config.ROOM_IDLE_TIMEOUT,
        Config.ROOM_IDLE_TIMEOUT,
        TimeUnit.SECONDS);

    // 初始化快照
    updateSnapshotAndBroadcast();
    logger.info("Room " + roomId + " started with Disruptor and independent idle checker.");
  }

  // 对外发布消息（由 Gateway 调用）
  public void post(Message msg) {
    if (!running.get()) return;
    publishEvent(msg);
  }

  private void publishEvent(Message msg) {
    long sequence;
    try {
      sequence = ringBuffer.tryNext();
    } catch (InsufficientCapacityException e) {
      logger.warn("Room " + roomId + " ring buffer full, dropping message: " + msg.type());
      return;
    }
    try {
      MessageEvent event = ringBuffer.get(sequence);
      event.setMessage(msg);
    } finally {
      ringBuffer.publish(sequence);
    }
  }

  private void handleMessage(Message msg) {
    switch (msg.type()) {
      case "JOIN":
        JoinRoomMsg join = (JoinRoomMsg) msg;
        boolean joined = state.addPlayer(join.username());
        if (joined) {
          ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
          resp.put("cmd", "JOIN_OK");
          resp.put("roomId", roomId);
          notifier.notifyPlayer(join.username(), resp.toString());
          notifier.onJoinResult(join.username(), roomId, true);
          logger.info("Player " + join.username() + " joined room " + roomId);
          if (onStatusChange != null) onStatusChange.run();
        } else {
          ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
          resp.put("cmd", "JOIN_FAIL");
          resp.put("message", "Room is full or join failed");
          notifier.notifyPlayer(join.username(), resp.toString());
          notifier.onJoinResult(join.username(), -1, false);
        }
        break;
      case "INPUT":
        InputMsg input = (InputMsg) msg;
        state.updateDirection(input.username(), input.direction());
        break;
      case "LEAVE":
        LeaveRoomMsg leave = (LeaveRoomMsg) msg;
        state.removePlayer(leave.username());
        notifier.onLeave(leave.username());
        logger.info("Player " + leave.username() + " left room " + roomId);
        if (onStatusChange != null) onStatusChange.run();
        break;
      default:
        logger.warn("Unknown message type: " + msg.type());
    }
    updateSnapshotAndBroadcast();
  }

  private void doGameTick() {
    // 更新活跃时间（只要房间非空）
    if (!state.isEmpty()) {
      lastActiveTime = System.currentTimeMillis();
    }

    // 记录死亡玩家
    Map<String, Boolean> wasAlive = new HashMap<>();
    for (GameState.Player p : state.getPlayers()) {
      wasAlive.put(p.username, !p.isDead);
    }

    state.update();

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
        notifier.updateHighScore(username, player.score);
      }
      state.removePlayer(username);
      notifier.onLeave(username);
      notifier.notifyPlayer(username, "{\"cmd\":\"YOU_DIED\"}");
      logger.debug("Player " + username + " died, score：" + (player != null ? player.score : 0));
    }

    updateSnapshotAndBroadcast();

    if (!diedPlayers.isEmpty() && onStatusChange != null) {
      onStatusChange.run();
    }

    // 空闲检查已移至独立调度器，此处不再调用 stop()
  }

  /** 独立线程中的空闲检查，当房间为空且超时后调用 stop() */
  private void checkIdleAndStop() {
    if (!running.get()) return;

    if (state.isEmpty()) {
      long idleMillis = System.currentTimeMillis() - lastActiveTime;
      if (idleMillis > Config.ROOM_IDLE_TIMEOUT * 1000L) {
        logger.info("Room " + roomId + " idle timeout, stopping from idle check thread.");
        stop(); // 在独立线程中调用，安全
      }
    }
  }

  private void updateSnapshotAndBroadcast() {
    cachedSnapshot = state.snapshot(null);
    if (cachedSnapshot == null) return;
    String json = new Serializer().serialize(cachedSnapshot);
    if (json == null) return;
    for (GameStateData.PlayerInfo player : cachedSnapshot.players) {
      if (player != null && !player.isDead) {
        notifier.notifyPlayer(player.name, json);
      }
    }
  }

  public GameStateData getSnapshot(String username) {
    return cachedSnapshot;
  }

  public int getRoomId() {
    return roomId;
  }

  public boolean isRunning() {
    return running.get();
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) return;
    logger.info("Room " + roomId + " is stopping...");

    // 通知房间内所有玩家房间已关闭，并重置 roomId
    List<GameState.Player> players = state.getPlayers();
    if (!players.isEmpty()) {
      logger.info("Notifying " + players.size() + " players in room " + roomId);
      for (GameState.Player p : players) {
        notifier.onLeave(p.username);
        notifier.notifyPlayer(p.username, "{\"cmd\":\"ROOM_CLOSED\"}");
      }
    }

    // 状态变更回调（更新房间列表）
    if (onStatusChange != null) {
      try {
        logger.info("Triggering room list update via onStatusChange for room " + roomId);
        onStatusChange.run();
      } catch (Exception e) {
        logger.error(
            "Error during onStatusChange callback for room " + roomId + ": " + e.getMessage());
      }
    } else {
      logger.warn("onStatusChange is null, cannot update room list");
    }

    // 关闭调度器
    tickScheduler.shutdown();
    idleCheckScheduler.shutdown();
    try {
      if (!tickScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        tickScheduler.shutdownNow();
      }
      if (!idleCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        idleCheckScheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      tickScheduler.shutdownNow();
      idleCheckScheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }

    // 关闭 Disruptor（此时不在其事件处理线程内，安全）
    disruptor.shutdown();
    logger.info("Room " + roomId + " destroyed.");

    // 调用销毁回调（确保房间从管理器中移除）
    if (destroyCallback != null) {
      logger.info("Calling destroyCallback.onRoomDestroyed for room " + roomId);
      destroyCallback.onRoomDestroyed(roomId, this);
    } else {
      logger.error("destroyCallback is NULL, room will not be removed from manager!");
    }
  }
}
