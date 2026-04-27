package snake.application.gateway.session;

import io.netty.channel.Channel;
import snake.common.ISession;

public class ClientSession implements ISession {
  public String username;
  public volatile int roomId = -1;
  public volatile long lastHeartbeat;
  public volatile long lastPingSent;
  public volatile boolean pendingPong;

  private final Channel channel;
  private final String sessionId;

  public ClientSession(Channel channel) {
    this.channel = channel;
    this.sessionId = channel.id().asShortText();
    this.lastHeartbeat = System.currentTimeMillis() / 1000;
    this.lastPingSent = 0;
    this.pendingPong = false;
  }

  @Override
  public void sendMessage(String message) {
    if (!isActive()) return;
    channel.writeAndFlush(message); // 会被 ProtocolFrameEncoder 处理
  }

  @Override
  public void sendBinary(byte[] data) {
    if (!isActive()) return;
    channel.writeAndFlush(data); // 会被 ProtocolFrameEncoder 处理
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
}
