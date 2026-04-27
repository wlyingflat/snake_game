package snake.common;

public interface ISession {
  void sendMessage(String message);

  void sendBinary(byte[] data);

  void close();

  String getSessionId();
}
