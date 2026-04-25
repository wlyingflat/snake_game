package snake.gateway.session;

import io.netty.channel.Channel;
import snake.network.ISession;

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
    // 直接发送字符串，由 Netty 的 LengthFieldPrepender + StringEncoder 自动添加长度头
    channel.writeAndFlush(message);
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
