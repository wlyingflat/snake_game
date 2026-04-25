package snake.actor;

import java.util.concurrent.atomic.AtomicBoolean;
import snake.base.*;
import snake.distributed.DistributedCoordinator;
import snake.event.KafkaEventProducer;
import snake.game.event.TickMessage;
import snake.game.state.GameState;
import snake.mq.MessageBus;

/**
 * 重构后的 GameActor 仅负责： - 组合各个组件（ActorEventLoop, ActorScheduler, GameMessageHandler,
 * GameTickProcessor） - 提供外部 API（postMessage, getSnapshot, stop, isRunning） - 协调组件间的交互
 */
public class GameActor {
  private final int roomId;
  private final String workerId;
  private final ActorEventLoop eventLoop;
  private final ActorScheduler scheduler;
  private final GameTickProcessor tickProcessor;
  private final GameMessageHandler messageHandler;
  private final ActorNotifier notifier;
  private final DistributedCoordinator coordinator;
  private final GameState state; // 暴露给外部获取玩家列表等
  private final Runnable onStatusChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private volatile long lastActiveTime = System.currentTimeMillis();
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

    this.state = new GameState(roomId);
    this.notifier = new ActorNotifier(coordinator, eventProducer, messageBus);

    // 创建消息处理器和 tick 处理器
    this.tickProcessor = new GameTickProcessor(roomId, state, notifier, onStatusChange);
    this.messageHandler =
        new GameMessageHandler(roomId, state, notifier, coordinator, tickProcessor, onStatusChange);

    // 创建事件循环，将自己实现的 MessageHandler 注入
    this.eventLoop =
        new ActorEventLoop(
            roomId,
            new ActorEventLoop.MessageHandler() {
              @Override
              public void onTick() {
                // 每次 tick 更新时间戳
                if (!state.isEmpty()) {
                  lastActiveTime = System.currentTimeMillis();
                }
                tickProcessor.processTick();
              }

              @Override
              public void onMessage(EnhancedMessage msg) {
                messageHandler.handle(msg);
              }
            });

    // 创建调度器，将任务进行绑定
    this.scheduler =
        new ActorScheduler(
            roomId, () -> eventLoop.publishEvent(new TickMessage()), this::checkIdleAndStop);

    // 启动 Disruptor 和调度器
    eventLoop.start();
    scheduler.start();

    logger.info("Actor " + roomId + " started on worker " + this.workerId);
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

  public GameStateData getSnapshot(String username) {
    return tickProcessor.getCachedSnapshot();
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) return;
    logger.info("Actor " + roomId + " is stopping...");

    // 通知所有玩家
    for (GameState.Player p : state.getPlayers()) {
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
      long idleMillis = System.currentTimeMillis() - lastActiveTime;
      if (idleMillis > Config.ROOM_IDLE_TIMEOUT * 1000L) {
        logger.info("Actor " + roomId + " idle timeout, stopping.");
        stop();
      }
    }
  }
}
