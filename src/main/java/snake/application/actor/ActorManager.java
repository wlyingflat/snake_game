package snake.application.actor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
    GameActor existing = actors.get(roomId);
    if (existing != null) {
      return existing;
    }

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

    final AtomicReference<GameActor> actorRef = new AtomicReference<>();
    GameActor actor =
        new GameActor(
            roomId,
            coordinator,
            workerId,
            eventProducer,
            messageBus,
            () -> {
              GameActor a = actorRef.get();
              if (a != null && a.isRunning()) {
                updateRoomInfo(roomId, a);
              } else {
                logger.info("Actor " + roomId + " stopped, cleaning up...");
                removeActor(roomId);
              }
              if (onActorStatusChange != null) {
                onActorStatusChange.run();
              }
              // 房间状态变化（玩家加入/离开/关闭）→ 广播房间列表更新
              if (messageBus != null) {
                messageBus.publishRoomListUpdate();
              }
            });

    actorRef.set(actor);

    GameActor previous = actors.putIfAbsent(roomId, actor);
    if (previous != null) {
      actor.stop();
      return previous;
    }

    // 新房间创建，广播更新
    if (messageBus != null) {
      messageBus.publishRoomListUpdate();
    }

    logger.info("Actor " + roomId + " created on worker " + workerId);
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
    // 房间删除，广播更新
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
