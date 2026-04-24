package snake.auth;

public interface IGatewayProcessManager {
  void startGateway(int port);

  void stopGateway();
}
