package snake.application.worker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import snake.application.actor.ActorManager;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.event.KafkaEventProducer;
import snake.infrastructure.messaging.MessageBus;

public class GameWorker {
  private final String workerId;
  private final ActorManager actorManager;
  private final DistributedCoordinator coordinator;
  private final ILogger logger = Logger.getInstance();
  private final ExecutorService dispatchPool;
  private final MessageBus messageBus;
  private final KafkaEventProducer eventProducer;
  private final WorkerMessageRouter router;
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

    RoomService roomService = new RoomService(actorManager, coordinator, messageBus);
    this.router = new WorkerMessageRouter(actorManager, messageBus, roomService);

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
    messageBus.startWorkerConsumer(
        workerId,
        rawMsg -> {
          dispatchPool.submit(() -> router.route(rawMsg));
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
