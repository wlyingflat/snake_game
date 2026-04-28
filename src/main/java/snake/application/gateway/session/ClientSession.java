// snake/application/gateway/session/ClientSession.java
package snake.application.gateway.session;

import io.netty.channel.Channel;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import snake.common.ISession;

public class ClientSession implements ISession {
  public String username;
  public volatile int roomId = -1;

  private static final AtomicLongFieldUpdater<ClientSession> LAST_HEARTBEAT_UPDATER =
      AtomicLongFieldUpdater.newUpdater(ClientSession.class, "lastHeartbeat");

  public volatile long lastHeartbeat;
  public volatile long lastPingSent;
  public volatile boolean pendingPong;

  private final Channel channel;
  private final String sessionId;

  public ClientSession(Channel channel) {
    this.channel = channel;
    this.sessionId = channel.id().asShortText();
    LAST_HEARTBEAT_UPDATER.set(this, System.currentTimeMillis() / 1000);
    this.lastPingSent = 0;
    this.pendingPong = false;
  }

  @Override
  public void sendMessage(String message) {
    if (!isActive()) return;
    channel.writeAndFlush(message);
  }

  @Override
  public void sendBinary(byte[] data) {
    if (!isActive()) return;
    channel.writeAndFlush(data);
  }

  @Override
  public void close() {
    if (channel.isOpen()) {
      channel.close();
    }
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  public boolean isActive() {
    return channel.isActive();
  }

  public Channel getChannel() {
    return channel;
  }

  // 通过 FieldUpdater 更新 lastHeartbeat，减少 volatile 写开销
  public void refreshHeartbeat() {
    LAST_HEARTBEAT_UPDATER.set(this, System.currentTimeMillis() / 1000);
  }
}
