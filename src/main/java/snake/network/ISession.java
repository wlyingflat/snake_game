package snake.network;

public interface ISession {
  void sendMessage(String message);

  void close();

  String getSessionId();
}
