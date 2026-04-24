package snake.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.common.*;
import snake.util.ILogger;
import snake.util.Logger;

public class Room {
  private final int roomId;
  private final Disruptor<MessageEvent> disruptor;
  private final RingBuffer<MessageEvent> ringBuffer;
  private final GameState state;
  private final IGameClientNotifier notifier;
  private final IRoomDestroyCallback destroyCallback;
  private final Runnable onStatusChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ScheduledExecutorService tickScheduler =
      Executors.newSingleThreadScheduledExecutor();
  private volatile GameStateData cachedSnapshot;
  private long lastActiveTime;
  private final ILogger logger = Logger.getInstance();
  private final ObjectMapper mapper = new ObjectMapper();

  public Room(
      int roomId,
      IGameClientNotifier notifier,
      IRoomDestroyCallback destroyCallback,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.notifier = notifier;
    this.destroyCallback = destroyCallback;
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
            new YieldingWaitStrategy() // 低延迟，适合消费者少
            );

    // 事件处理器：处理消息和 tick
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

    // 启动定时器发布 Tick 事件
    tickScheduler.scheduleAtFixedRate(
        () -> {
          if (!running.get()) return;
          publishEvent(new TickMessage());
        },
        0,
        Config.TICK_INTERVAL_MS,
        TimeUnit.MILLISECONDS);

    // 初始化快照
    updateSnapshotAndBroadcast();
    logger.info("Room " + roomId + " started with Disruptor.");
  }

  // 对外发布消息（由 Gateway 调用）
  public void post(Message msg) {
    if (!running.get()) return;
    publishEvent(msg);
  }

  private void publishEvent(Message msg) {
    long sequence;
    try {
      sequence = ringBuffer.tryNext(); // 非阻塞，防止生产者积压
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
        if (state.addPlayer(join.username())) {
          ObjectNode resp = mapper.createObjectNode();
          resp.put("cmd", "JOIN_OK");
          resp.put("roomId", roomId);
          notifier.notifyPlayer(join.username(), resp.toString());
          logger.info("Player " + join.username() + " joined room " + roomId);
          if (onStatusChange != null) onStatusChange.run();
        } else {
          ObjectNode resp = mapper.createObjectNode();
          resp.put("cmd", "JOIN_FAIL");
          resp.put("message", "Room is full or join failed");
          notifier.notifyPlayer(join.username(), resp.toString());
        }
        break;
      case "INPUT":
        InputMsg input = (InputMsg) msg;
        state.updateDirection(input.username(), input.direction());
        break;
      case "LEAVE":
        LeaveRoomMsg leave = (LeaveRoomMsg) msg;
        state.removePlayer(leave.username());
        logger.info("Player " + leave.username() + " left room " + roomId);
        if (onStatusChange != null) onStatusChange.run();
        break;
      default:
        logger.warn("Unknown message type: " + msg.type());
    }
    // 状态变化后立即更新快照并广播（可选，也可在 tick 中统一广播）
    updateSnapshotAndBroadcast();
  }

  private void doGameTick() {
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
      state.removePlayer(username);
      notifier.notifyPlayer(username, "{\"cmd\":\"YOU_DIED\"}");
      logger.debug("Player " + username + " died, removed from room");
    }

    updateSnapshotAndBroadcast();

    if (!diedPlayers.isEmpty() && onStatusChange != null) {
      onStatusChange.run();
    }

    // 空闲检查
    if (state.isEmpty()) {
      if (System.currentTimeMillis() - lastActiveTime > Config.ROOM_IDLE_TIMEOUT * 1000L) {
        logger.info("Room " + roomId + " idle timeout, stopping.");
        stop();
      }
    } else {
      lastActiveTime = System.currentTimeMillis();
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
    tickScheduler.shutdown();
    disruptor.shutdown();
    logger.info("Room " + roomId + " destroyed.");
    if (destroyCallback != null) {
      destroyCallback.onRoomDestroyed(roomId, this);
    }
  }
}
