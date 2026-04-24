// snake/core/MessageDispatcher.java
package snake.core;

import java.util.Collection;

/**
 * 消息分发接口，由传输层（如网关）实现，供领域层调用。 领域层（RoomManager、Room）只依赖此接口发送消息给客户端， 不关心具体实现（NIO、WebSocket、Kafka 等）。
 */
public interface MessageDispatcher {
  /**
   * 向指定用户发送一条消息（JSON 字符串）。 实现需保证线程安全，处理用户离线/会话不存在的情况。
   *
   * @param username 目标用户名
   * @param message 消息内容（JSON 字符串）
   */
  void sendToUser(String username, String message);

  /**
   * 向一组用户广播消息（默认实现为逐个发送）。 子类可重写以优化（如批量发送）。
   *
   * @param usernames 目标用户名集合
   * @param message 消息内容（JSON 字符串）
   */
  default void sendToUsers(Collection<String> usernames, String message) {
    for (String username : usernames) {
      sendToUser(username, message);
    }
  }

  /**
   * 可选：用户断开连接时的清理回调，由传输层调用，通知领域层清理玩家状态。
   *
   * @param username 断开的用户名
   */
  default void onUserDisconnected(String username) {
    // 默认空实现，子类可覆盖
  }
}
