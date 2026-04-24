package snake.gateway;

public interface HeartbeatService {
  void start();

  void stop();

  void refresh(ClientSession session);

  void onHeartbeatTimeout(ClientSession session);
}
