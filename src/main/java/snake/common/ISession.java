package snake.common;

public interface ISession {
  void sendMessage(String message);

  void sendBinary(byte[] data);

  void close();

  String getSessionId();

  /** 发送 Protobuf 二进制消息（默认实现直接调用 sendBinary 即可） */
  default void sendProtobuf(byte[] data) {
    sendBinary(data);
  }
}
