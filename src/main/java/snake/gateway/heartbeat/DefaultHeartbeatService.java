package snake.gateway.heartbeat;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;
import snake.gateway.session.ClientSession;

public class DefaultHeartbeatService implements HeartbeatService {
  private final HashedWheelTimer timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
  private final ConcurrentHashMap<ClientSession, Timeout> sessionTimeouts =
      new ConcurrentHashMap<>();
  private final ILogger logger = Logger.getInstance();
  private final Consumer<ClientSession> onTimeoutCallback;
  private final DistributedCoordinator coordinator;
  private ScheduledExecutorService pingSender;
  private volatile boolean running = true;

  public DefaultHeartbeatService(
      Consumer<ClientSession> onTimeoutCallback, DistributedCoordinator coordinator) {
    this.onTimeoutCallback = onTimeoutCallback;
    this.coordinator = coordinator;
  }

  @Override
  public void start() {
    pingSender =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("heartbeat-ping-sender");
              t.setDaemon(true);
              return t;
            });
    pingSender.scheduleAtFixedRate(
        this::sendPings, Config.HEARTBEAT_INTERVAL, Config.HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    logger.info("HeartbeatService started");
  }

  @Override
  public void stop() {
    running = false;
    if (pingSender != null) pingSender.shutdownNow();
    for (Timeout timeout : sessionTimeouts.values()) {
      if (timeout != null && !timeout.isCancelled()) {
        timeout.cancel();
      }
    }
    sessionTimeouts.clear();
    timer.stop();
    logger.info("HeartbeatService stopped");
  }

  @Override
  public void refresh(ClientSession session) {
    if (session == null || session.closed) return;

    Timeout old = sessionTimeouts.remove(session);
    if (old != null) old.cancel();

    Timeout newTimeout =
        timer.newTimeout(
            timeout -> {
              logger.warn("Client " + session.username + " heartbeat timeout, closing session");
              if (coordinator != null && session.username != null) {
                coordinator.markOffline(session.username);
                coordinator.removePlayerLocation(session.username);
              }
              if (onTimeoutCallback != null) {
                onTimeoutCallback.accept(session);
              }
            },
            Config.HEARTBEAT_TIMEOUT,
            TimeUnit.SECONDS);
    sessionTimeouts.put(session, newTimeout);

    if (coordinator != null && session.username != null) {
      coordinator.refreshOnline(session.username);
      coordinator.refreshPlayerLocation(session.username);
    }
  }

  @Override
  public void onHeartbeatTimeout(ClientSession session) {
    if (session == null || session.closed) return;
    logger.warn("Client " + session.username + " heartbeat timeout, closing session");
    if (onTimeoutCallback != null) onTimeoutCallback.accept(session);
  }

  @Override
  public void remove(ClientSession session) {
    if (session == null) return;
    Timeout timeout = sessionTimeouts.remove(session);
    if (timeout != null) {
      timeout.cancel();
    }
    logger.debug("Removed heartbeat tracking for session: " + session.getSessionId());

    if (coordinator != null && session.username != null) {
      coordinator.markOffline(session.username);
      coordinator.removePlayerLocation(session.username);
    }
  }

  private void sendPings() {
    if (!running) return;
    long nowSec = System.currentTimeMillis() / 1000;
    for (ClientSession session : sessionTimeouts.keySet()) {
      if (session == null || session.closed || session.username == null) continue;

      if (!session.pendingPong && (nowSec - session.lastHeartbeat) >= Config.HEARTBEAT_INTERVAL) {
        session.sendMessage("{\"cmd\":\"PING\"}");
        session.pendingPong = true;
        session.lastPingSent = nowSec;
        refresh(session);
      }

      if (coordinator != null && session.username != null) {
        coordinator.refreshOnline(session.username);
        coordinator.refreshPlayerLocation(session.username);
      }
    }
  }
}
