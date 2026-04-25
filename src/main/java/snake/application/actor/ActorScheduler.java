package snake.application.actor;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.common.Config;
import snake.common.ILogger;
import snake.common.Logger;

/**
 * 优化后的 Actor 调度器： - Tick 任务使用全局共享的 ScheduledExecutorService，保证 200ms 精确周期 - 空闲检查使用全局静态
 * HashedWheelTimer，单线程支撑海量周期性任务，资源极省
 */
public class ActorScheduler {

  // ============ 全局共享资源（所有房间共享） ============
  private static final ScheduledExecutorService sharedTickScheduler;
  private static final HashedWheelTimer sharedIdleTimer;
  private static final ILogger logger = Logger.getInstance();

  static {
    // Tick 线程池：核心线程数 = CPU 核数，保证低延迟
    int tickThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
    sharedTickScheduler =
        Executors.newScheduledThreadPool(
            tickThreads,
            r -> {
              Thread t = new Thread(r, "shared-tick");
              t.setDaemon(true);
              return t;
            });

    // 空闲检查时间轮：单线程，tickDuration=100ms，512 个槽位
    sharedIdleTimer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
  }

  /** 优雅关闭全局调度器（由主程序在系统退出时调用） */
  public static void shutdownGlobal() {
    sharedTickScheduler.shutdown();
    sharedIdleTimer.stop();
    logger.info("Global scheduler and idle timer shut down");
  }

  // ============ 实例字段 ============
  private final int roomId;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Runnable tickTask;
  private final Runnable idleCheckTask;

  // 任务句柄，用于取消
  private ScheduledFuture<?> tickFuture;
  private Timeout idleTimeout;

  public ActorScheduler(int roomId, Runnable tickTask, Runnable idleCheckTask) {
    this.roomId = roomId;
    this.tickTask = tickTask;
    this.idleCheckTask = idleCheckTask;
  }

  /** 启动调度：注册 tick 和空闲检查任务 */
  public void start() {
    // Tick 任务：固定频率，带异常保护
    tickFuture =
        sharedTickScheduler.scheduleAtFixedRate(
            () -> {
              if (!running.get()) return;
              try {
                tickTask.run();
              } catch (Exception e) {
                logger.error("Tick error in room " + roomId + ": " + e.getMessage() + e);
              }
            },
            0,
            Config.TICK_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

    // 空闲检查：使用时间轮，任务执行后自动续期
    scheduleIdleCheck();
  }

  /** 时间轮版本的周期性空闲检查：每次执行完毕后重新注册下一次 */
  private void scheduleIdleCheck() {
    idleTimeout =
        sharedIdleTimer.newTimeout(
            timeout -> {
              if (!running.get()) return;
              try {
                idleCheckTask.run();
              } catch (Exception e) {
                logger.error("Idle check error in room " + roomId + ": " + e.getMessage() + e);
              }
              // 无论是否发生异常，继续下一轮检查（除非已停止）
              if (running.get()) {
                scheduleIdleCheck();
              }
            },
            Config.ROOM_IDLE_TIMEOUT,
            TimeUnit.SECONDS);
  }

  /** 停止调度：取消所有定时任务 */
  public void stop() {
    if (!running.compareAndSet(true, false)) return;

    // 取消 tick 调度
    if (tickFuture != null) {
      tickFuture.cancel(false);
      tickFuture = null;
    }

    // 取消时间轮任务
    if (idleTimeout != null) {
      idleTimeout.cancel();
      idleTimeout = null;
    }
  }
}
