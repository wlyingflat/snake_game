package snake.application.actor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import snake.common.*;
import snake.distributed.DistributedCoordinator;
import snake.domain.game.AgarGameState;
import snake.domain.game.GameState;
import snake.domain.game.TickMessage;
import snake.infrastructure.event.KafkaEventProducer;
import snake.infrastructure.messaging.MessageBus;

public class GameActor {
  private final int roomId;
  private final String workerId;
  private final ActorEventLoop eventLoop;
  private final ActorScheduler scheduler;
  private final GameTickProcessor tickProcessor;
  private final GameMessageHandler messageHandler;
  private final ActorNotifier notifier;
  private final DistributedCoordinator coordinator;
  private final AgarGameState state; // ← 吞噬游戏状态
  private final Runnable onStatusChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicLong lastActiveTime = new AtomicLong(System.currentTimeMillis());
  private final ILogger logger = Logger.getInstance();

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

    // 吞噬游戏地图：3000x3000
    this.state = new AgarGameState(roomId, 3000, 3000);
    this.notifier = new ActorNotifier(coordinator, eventProducer, messageBus);

    this.tickProcessor = new GameTickProcessor(roomId, state, notifier);
    this.messageHandler =
        new GameMessageHandler(roomId, state, notifier, coordinator, onStatusChange);

    this.eventLoop =
        new ActorEventLoop(
            roomId,
            new ActorEventLoop.MessageHandler() {
              @Override
              public void onTick() {
                if (!state.isEmpty()) {
                  lastActiveTime.set(System.currentTimeMillis());
                }
                tickProcessor.processTick();
              }

              @Override
              public void onMessage(EnhancedMessage msg) {
                messageHandler.handle(msg);
              }
            });

    this.scheduler =
        new ActorScheduler(
            roomId, () -> eventLoop.publishEvent(new TickMessage()), this::checkIdleAndStop);

    logger.info("Agar Actor " + roomId + " constructed on worker " + this.workerId);
  }

  public void start() {
    if (started.compareAndSet(false, true)) {
      eventLoop.start();
      scheduler.start();
      logger.info("Agar Actor " + roomId + " started on worker " + this.workerId);
    }
  }

  public void postMessage(EnhancedMessage msg) {
    if (!running.get()) return;
    eventLoop.publishEvent(msg);
  }

  public int getRoomId() {
    return roomId;
  }

  public boolean isRunning() {
    return running.get();
  }

  // 获取快照（用于房间状态，吞噬游戏可返回玩家数）
  public int getActivePlayers() {
    return state.getActivePlayers();
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) return;
    logger.info("Actor " + roomId + " is stopping...");

    // 通知所有玩家（通过 state.getActiveUsernames()）
    for (GameState.Player p : state.getActiveUsernames()) {
      notifier.sendToPlayer(p.username, null, "{\"cmd\":\"ROOM_CLOSED\"}");
      coordinator.removePlayerLocation(p.username);
    }

    if (onStatusChange != null) {
      try {
        onStatusChange.run();
      } catch (Exception ignored) {
      }
    }

    scheduler.stop();
    eventLoop.shutdown();
    logger.info("Actor " + roomId + " destroyed.");
  }

  private void checkIdleAndStop() {
    if (!running.get()) return;
    if (state.isEmpty()) {
      long idleMillis = System.currentTimeMillis() - lastActiveTime.get();
      if (idleMillis > Config.ROOM_IDLE_TIMEOUT * 1000L) {
        logger.info("Actor " + roomId + " idle timeout, stopping.");
        stop();
      }
    }
  }
}
