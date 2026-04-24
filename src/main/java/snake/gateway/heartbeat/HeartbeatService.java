package snake.gateway.heartbeat;

import snake.gateway.session.ClientSession;

// snake/gateway/HeartbeatService.java
public interface HeartbeatService {
  void start();

  void stop();

  void refresh(ClientSession session);

  void onHeartbeatTimeout(ClientSession session);

  void remove(ClientSession session); // 新增
}
