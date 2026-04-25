package snake.application.actor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.common.Config;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.event.KafkaEventProducer;
import snake.infrastructure.messaging.MessageBus;

public class ActorManager {
  private final Map<Integer, GameActor> actors = new ConcurrentHashMap<>();
  private final DistributedCoordinator coordinator;
  private final String workerId;
  private final KafkaEventProducer eventProducer;
  private final MessageBus messageBus;
  private final ILogger logger = Logger.getInstance();
  private Runnable onActorStatusChange;

  public ActorManager(
      DistributedCoordinator coordinator,
      String workerId,
      KafkaEventProducer eventProducer,
      MessageBus messageBus) {
    this.coordinator = coordinator;
    this.workerId = workerId;
    this.eventProducer = eventProducer;
    this.messageBus = messageBus;
  }

  public void setOnActorStatusChange(Runnable callback) {
    this.onActorStatusChange = callback;
  }

  public GameActor getOrCreateActor(int roomId) {
    // 快速本地检查
    GameActor existing = actors.get(roomId);
    if (existing != null) {
      return existing;
    }

    // 分布式协调（Redis），保证跨节点唯一
    if (coordinator.roomExists(roomId)) {
      String existingWorker = coordinator.getRoomWorker(roomId);
      if (existingWorker != null && !existingWorker.equals(workerId)) {
        logger.warn("Room " + roomId + " already assigned to worker " + existingWorker);
        return null;
      }
    }

    boolean registered = coordinator.tryCreateRoom(roomId, Config.MAX_PLAYERS_PER_ROOM);
    if (!registered) {
      logger.warn("Room " + roomId + " already exists in Redis");
      return null;
    }

    coordinator.assignRoomToWorker(roomId, workerId);

    // 原子创建本地 Actor，避免重复构造资源
    AtomicBoolean created = new AtomicBoolean(false);
    GameActor actor =
        actors.computeIfAbsent(
            roomId,
            id -> {
              GameActor newActor =
                  new GameActor(
                      id,
                      coordinator,
                      workerId,
                      eventProducer,
                      messageBus,
                      () -> {
                        // 状态变化回调：更新房间信息或清理
                        GameActor a = actors.get(id);
                        if (a != null && a.isRunning()) {
                          updateRoomInfo(id, a);
                        } else {
                          logger.info("Actor " + id + " stopped, cleaning up...");
                          removeActor(id);
                        }
                        if (onActorStatusChange != null) {
                          onActorStatusChange.run();
                        }
                        // 房间状态变化（玩家加入/离开/关闭）→ 广播房间列表更新
                        if (messageBus != null) {
                          messageBus.publishRoomListUpdate();
                        }
                      });
              newActor.start(); // 在原子插入前启动
              created.set(true);
              return newActor;
            });

    if (created.get()) {
      // 新房间创建，广播更新
      if (messageBus != null) {
        messageBus.publishRoomListUpdate();
      }
      logger.info("Actor " + roomId + " created on worker " + workerId);
    }
    return actor;
  }

  public GameActor getActor(int roomId) {
    return actors.get(roomId);
  }

  public void removeActor(int roomId) {
    GameActor actor = actors.remove(roomId);
    if (actor != null) {
      logger.info("Actor " + roomId + " removed from worker " + workerId);
      if (actor.isRunning()) {
        actor.stop();
      }
    }
    coordinator.deleteRoom(roomId);
    if (messageBus != null) {
      messageBus.publishRoomListUpdate();
    }
    logger.info("Room " + roomId + " deleted from Redis");

    if (onActorStatusChange != null) {
      onActorStatusChange.run();
    }
  }

  public Map<Integer, GameActor> getAllActors() {
    return actors;
  }

  public void stopAllActors() {
    for (Map.Entry<Integer, GameActor> entry : actors.entrySet()) {
      try {
        entry.getValue().stop();
        coordinator.deleteRoom(entry.getKey());
      } catch (Exception e) {
        logger.error("Error stopping actor " + entry.getKey() + ": " + e.getMessage());
      }
    }
    actors.clear();
  }

  private void updateRoomInfo(int roomId, GameActor actor) {
    if (actor == null || !actor.isRunning()) return;
    var snapshot = actor.getSnapshot(null);
    if (snapshot != null) {
      boolean isFull = snapshot.activePlayers >= Config.MAX_PLAYERS_PER_ROOM;
      coordinator.updateRoomInfo(roomId, snapshot.activePlayers, isFull);
    }
  }
}
