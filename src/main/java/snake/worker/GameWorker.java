package snake.worker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.actor.EnhancedMessage;
import snake.actor.GameActor;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;
import snake.event.KafkaEventProducer;
import snake.mq.MessageBus; // 新增

public class GameWorker {
  private final String workerId;
  private final ActorManager actorManager;
  private final DistributedCoordinator coordinator;
  private final ILogger logger = Logger.getInstance();
  private final ExecutorService dispatchPool;
  private final MessageBus messageBus; // 新增
  private final KafkaEventProducer eventProducer;
  private volatile boolean running = false;

  public GameWorker(
      String workerId,
      DistributedCoordinator coordinator,
      MessageBus messageBus,
      KafkaEventProducer eventProducer) {
    this.workerId = workerId;
    this.coordinator = coordinator;
    this.eventProducer = eventProducer;
    this.messageBus = messageBus;
    this.actorManager = new ActorManager(coordinator, workerId, eventProducer, messageBus);
    this.dispatchPool =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
              Thread t = new Thread(r, "worker-" + workerId + "-dispatch");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() throws Exception {
    if (running) return;
    running = true;

    coordinator.registerWorker(workerId);

    actorManager.setOnActorStatusChange(
        () -> {
          coordinator.publishRoomListUpdate();
        });

    // 通过 RabbitMQ 接收指令
    messageBus.startWorkerConsumer(
        workerId,
        rawMsg -> {
          dispatchPool.submit(() -> dispatchToActor(rawMsg));
        });

    logger.info("Worker " + workerId + " started, listening for messages via RabbitMQ");
  }

  public void stop() {
    running = false;

    actorManager.stopAllActors();
    dispatchPool.shutdown();
    try {
      dispatchPool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      dispatchPool.shutdownNow();
      Thread.currentThread().interrupt();
    }

    coordinator.unregisterWorker(workerId);

    // 关闭 Kafka 生产者，确保所有待发送事件被刷新
    if (eventProducer != null) {
      try {
        eventProducer.close(); // close() 内部会调用 flush() 并关闭连接
      } catch (Exception e) {
        logger.error("Failed to close Kafka producer: " + e.getMessage());
      }
    }

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
