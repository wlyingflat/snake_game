package snake.application.worker;

import snake.application.actor.ActorManager;
import snake.application.actor.EnhancedMessage;
import snake.application.actor.GameActor;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.messaging.MessageBus;

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

  // 现在接收 byte[]
  public void route(byte[] rawData) {
    EnhancedMessage msg = null;
    try {
      msg = EnhancedMessage.fromProtobuf(rawData);
      if (msg == null) return;

      String command = msg.getCommand();
      if ("CREATE".equals(command)) {
        roomService.createRoom(msg);
      } else {
        GameActor actor = actorManager.getActor(msg.getRoomId());
        if (actor == null || !actor.isRunning()) {
          // 返回错误给玩家
          String error = "{\"cmd\":\"ERROR\",\"message\":\"Room not found\"}";
          if (messageBus != null) {
            messageBus.publishToPlayer(msg.getGatewayId(), msg.getUsername(), error);
          }
          return;
        }
        actor.postMessage(msg);
        msg = null; // 已提交，防止回收
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
