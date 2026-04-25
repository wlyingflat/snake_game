package snake.application.worker;

import snake.application.actor.ActorManager;
import snake.application.actor.EnhancedMessage;
import snake.application.actor.GameActor;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.messaging.MessageBus;

/** 负责解析来自消息总线的原始消息，并根据命令路由。 */
public class WorkerMessageRouter {
  private final ActorManager actorManager;
  private final MessageBus messageBus;
  private final RoomService roomService;
  private final ILogger logger = Logger.getInstance();

  public WorkerMessageRouter(
      ActorManager actorManager, MessageBus messageBus, RoomService roomService) {
    this.actorManager = actorManager;
    this.messageBus = messageBus;
    this.roomService = roomService;
  }

  /** 处理一条消息，在调用者的线程池中执行。 */
  public void route(String rawMsg) {
    EnhancedMessage msg = null;
    try {
      msg = EnhancedMessage.fromJson(rawMsg);
      if (msg == null) return;

      String command = msg.getCommand();
      if ("CREATE".equals(command)) {
        roomService.createRoom(msg);
      } else {
        GameActor actor = actorManager.getActor(msg.getRoomId());
        if (actor == null || !actor.isRunning()) {
          String error = "{\"cmd\":\"ERROR\",\"message\":\"Room not found\"}";
          if (messageBus != null) {
            messageBus.publishToPlayer(msg.getGatewayId(), msg.getUsername(), error);
          }
          return;
        }
        actor.postMessage(msg);
        msg = null; // 已提交，不许回收
      }
    } catch (Exception e) {
      logger.error("Failed to dispatch message: " + e.getMessage());
    } finally {
      if (msg != null) {
        msg.recycle();
      }
    }
  }
}
