package snake.worker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.actor.EnhancedMessage;
import snake.actor.GameActor;
import snake.base.ILeaderboardRepository;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;

/**
 * Worker 层 - Actor 调度器
 *
 * <p>职责： 1. 管理本节点的所有 Actor 2. 接收来自 Gateway 的消息并分发到正确的 Actor 3. 管理 Actor 的生命周期
 */
public class GameWorker {
  private final String workerId;
  private final ActorManager actorManager;
  private final DistributedCoordinator coordinator;
  private final ILogger logger = Logger.getInstance();
  private final ExecutorService dispatchPool;
  private final ILeaderboardRepository leaderboardRepo;
  private volatile boolean running = false;
  private int messageListenerId;

  public GameWorker(
      String workerId, DistributedCoordinator coordinator, ILeaderboardRepository leaderboardRepo) {
    this.workerId = workerId;
    this.coordinator = coordinator;
    this.leaderboardRepo = leaderboardRepo;
    this.actorManager = new ActorManager(coordinator, workerId, leaderboardRepo);
    this.dispatchPool =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
              Thread t = new Thread(r, "worker-" + workerId + "-dispatch");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    if (running) return;
    running = true;

    coordinator.registerWorker(workerId);

    actorManager.setOnActorStatusChange(
        () -> {
          coordinator.publishRoomListUpdate();
        });

    messageListenerId =
        coordinator.subscribeWorkerMessages(
            workerId,
            (channel, rawMsg) -> {
              dispatchPool.submit(() -> dispatchToActor(rawMsg));
            });

    logger.info("Worker " + workerId + " started, listening for messages");
  }

  public void stop() {
    running = false;

    if (messageListenerId > 0) {
      coordinator.unsubscribeWorkerMessages(workerId, messageListenerId);
    }

    actorManager.stopAllActors();
    dispatchPool.shutdown();
    try {
      dispatchPool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      dispatchPool.shutdownNow();
    }

    coordinator.unregisterWorker(workerId);
    logger.info("Worker " + workerId + " stopped");
  }

  private void dispatchToActor(String rawMsg) {
    try {
      EnhancedMessage msg = EnhancedMessage.fromJson(rawMsg);
      if (msg == null) return;

      int roomId = msg.getRoomId();
      String command = msg.getCommand();

      if ("CREATE".equals(command)) {
        handleCreateRoom(msg);
        return;
      }

      GameActor actor = actorManager.getActor(roomId);
      if (actor == null || !actor.isRunning()) {
        String error = "{\"cmd\":\"ERROR\",\"message\":\"Room not found\"}";
        coordinator.publishToGateway(msg.getGatewayId(), msg.getUsername(), error);
        return;
      }

      actor.postMessage(msg);

    } catch (Exception e) {
      logger.error("Failed to dispatch message: " + e.getMessage());
    }
  }

  private void handleCreateRoom(EnhancedMessage msg) {
    int roomId = msg.getRoomId();

    if (coordinator.roomExists(roomId)) {
      EnhancedMessage joinMsg =
          new EnhancedMessage(
              "JOIN", msg.getUsername(), roomId, msg.getGatewayId(), msg.getRawMessage());
      dispatchToActor(joinMsg.toJson());
      return;
    }

    GameActor actor = actorManager.getOrCreateActor(roomId);
    if (actor == null) {
      String error = "{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}";
      coordinator.publishToGateway(msg.getGatewayId(), msg.getUsername(), error);
      return;
    }

    EnhancedMessage joinMsg =
        new EnhancedMessage(
            "JOIN", msg.getUsername(), roomId, msg.getGatewayId(), msg.getRawMessage());
    actor.postMessage(joinMsg);
    logger.info("Room " + roomId + " created by " + msg.getUsername());
  }

  public ActorManager getActorManager() {
    return actorManager;
  }

  public String getWorkerId() {
    return workerId;
  }

  public boolean isRunning() {
    return running;
  }
}
