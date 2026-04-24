package snake.worker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import snake.actor.GameActor;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;
import snake.event.KafkaEventProducer;
import snake.mq.MessageBus;

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
                // Actor 还在运行，更新房间信息
                updateRoomInfo(roomId, a);
              } else {
                // Actor 已停止，清理 Redis 和本地注册
                logger.info("Actor " + roomId + " stopped, cleaning up...");
                removeActor(roomId);
              }
              if (onActorStatusChange != null) {
                onActorStatusChange.run();
              }
            });

    actorRef.set(actor);

    GameActor previous = actors.putIfAbsent(roomId, actor);
    if (previous != null) {
      actor.stop();
      return previous;
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
      // 确保 Actor 已停止
      if (actor.isRunning()) {
        actor.stop();
      }
    }
    // 清理 Redis
    coordinator.deleteRoom(roomId);
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
