package snake.server;

public interface IGatewayProcessManager {
  void startGateway(int port);

  void stopGateway();
}
