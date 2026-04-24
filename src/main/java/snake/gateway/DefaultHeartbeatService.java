package snake.gateway;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import snake.common.Config;
import snake.util.ILogger;
import snake.util.Logger;

public class DefaultHeartbeatService implements HeartbeatService {
  private final HashedWheelTimer timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
  private final ConcurrentHashMap<ClientSession, Timeout> sessionTimeouts =
      new ConcurrentHashMap<>();
  private final ILogger logger = Logger.getInstance();
  private final Consumer<ClientSession> onTimeoutCallback;
  private ScheduledExecutorService pingSender;
  private volatile boolean running = true;

  public DefaultHeartbeatService(Consumer<ClientSession> onTimeoutCallback) {
    this.onTimeoutCallback = onTimeoutCallback;
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
    timer.stop();
  }

  @Override
  public void refresh(ClientSession session) {
    if (session.closed) return;
    Timeout old = sessionTimeouts.remove(session);
    if (old != null) old.cancel();
    Timeout newTimeout =
        timer.newTimeout(
            timeout -> onTimeoutCallback.accept(session),
            Config.HEARTBEAT_TIMEOUT,
            TimeUnit.SECONDS);
    sessionTimeouts.put(session, newTimeout);
  }

  @Override
  public void onHeartbeatTimeout(ClientSession session) {
    if (session.closed) return;
    logger.warn("Client " + session.username + " heartbeat timeout, closing session");
    if (onTimeoutCallback != null) onTimeoutCallback.accept(session);
  }

  private void sendPings() {
    if (!running) return;
    long nowSec = System.currentTimeMillis() / 1000;
    for (ClientSession session : sessionTimeouts.keySet()) {
      if (session.username == null || session.closed) continue;
      if (!session.pendingPong && (nowSec - session.lastHeartbeat) >= Config.HEARTBEAT_INTERVAL) {
        session.sendMessage("{\"cmd\":\"PING\"}");
        session.pendingPong = true;
        session.lastPingSent = nowSec;
        refresh(session);
      }
    }
  }
}
