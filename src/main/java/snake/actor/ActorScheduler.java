package snake.actor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;

/** 管理 Actor 的 tick 调度和空闲检查调度， 通过回调通知 GameActor 执行相应逻辑。 */
public class ActorScheduler {
  private final int roomId;
  private final ILogger logger = Logger.getInstance();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ScheduledExecutorService tickScheduler;
  private final ScheduledExecutorService idleCheckScheduler;

  private final Runnable tickTask;
  private final Runnable idleCheckTask;

  public ActorScheduler(int roomId, Runnable tickTask, Runnable idleCheckTask) {
    this.roomId = roomId;
    this.tickTask = tickTask;
    this.idleCheckTask = idleCheckTask;

    this.tickScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "actor-" + roomId + "-tick");
              t.setDaemon(true);
              return t;
            });
    this.idleCheckScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "actor-" + roomId + "-idle");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    tickScheduler.scheduleAtFixedRate(
        () -> {
          if (running.get()) tickTask.run();
        },
        0,
        Config.TICK_INTERVAL_MS,
        TimeUnit.MILLISECONDS);

    idleCheckScheduler.scheduleAtFixedRate(
        () -> {
          if (running.get()) idleCheckTask.run();
        },
        Config.ROOM_IDLE_TIMEOUT,
        Config.ROOM_IDLE_TIMEOUT,
        TimeUnit.SECONDS);
  }

  public void stop() {
    running.set(false);
    tickScheduler.shutdown();
    idleCheckScheduler.shutdown();
    try {
      tickScheduler.awaitTermination(5, TimeUnit.SECONDS);
      idleCheckScheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
