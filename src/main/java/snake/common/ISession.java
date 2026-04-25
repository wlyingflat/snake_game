package snake.common;

public interface ISession {
  void sendMessage(String message);

  void close();

  String getSessionId();
}
