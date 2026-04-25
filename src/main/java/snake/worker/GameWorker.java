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
import snake.mq.MessageBus;

public class GameWorker {
  private final String workerId;
  private final ActorManager actorManager;
  private final DistributedCoordinator coordinator;
  private final ILogger logger = Logger.getInstance();
  private final ExecutorService dispatchPool;
  private final MessageBus messageBus;
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

    if (eventProducer != null) {
      try {
        eventProducer.close();
      } catch (Exception e) {
        logger.error("Failed to close Kafka producer: " + e.getMessage());
      }
    }

    logger.info("Worker " + workerId + " stopped");
  }

  private void dispatchToActor(String rawMsg) {
    EnhancedMessage msg = null;
    try {
      msg = EnhancedMessage.fromJson(rawMsg);
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
        if (messageBus != null) {
          messageBus.publishToPlayer(msg.getGatewayId(), msg.getUsername(), error);
        }
        return;
      }

      actor.postMessage(msg); // 将池化对象移交给 Disruptor，由它负责回收
      // 注意：不要在这里 msg.recycle()，因为已经 post 给 Disruptor 异步处理
      // 将 msg 置 null，避免 finally 中重复回收
      EnhancedMessage toRecycle = msg;
      msg = null;
      // 正常路径下不在这里回收，但如果在 post 之前异常，msg 需要回收
    } catch (Exception e) {
      logger.error("Failed to dispatch message: " + e.getMessage());
      if (msg != null) {
        msg.recycle();
      }
    }
  }

  private void handleCreateRoom(EnhancedMessage msg) {
    int roomId = msg.getRoomId();

    if (coordinator.roomExists(roomId)) {
      EnhancedMessage joinMsg =
          EnhancedMessage.newInstance()
              .init("JOIN", msg.getUsername(), roomId, msg.getGatewayId(), msg.getRawMessage());
      try {
        dispatchToActor(joinMsg.toJson());
      } finally {
        joinMsg.recycle();
      }
      return;
    }

    GameActor actor = actorManager.getOrCreateActor(roomId);
    if (actor == null) {
      String error = "{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}";
      if (messageBus != null) {
        messageBus.publishToPlayer(msg.getGatewayId(), msg.getUsername(), error);
      }
      return;
    }

    EnhancedMessage joinMsg =
        EnhancedMessage.newInstance()
            .init("JOIN", msg.getUsername(), roomId, msg.getGatewayId(), msg.getRawMessage());
    try {
      actor.postMessage(joinMsg);
      // 已提交到 Disruptor，由它负责回收，这里不能 recycle
      joinMsg = null; // 防止 finally 中回收
    } finally {
      if (joinMsg != null) {
        joinMsg.recycle();
      }
    }
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
