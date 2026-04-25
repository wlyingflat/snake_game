package snake.application.worker;

import snake.application.actor.ActorManager;
import snake.application.actor.EnhancedMessage;
import snake.application.actor.GameActor;
import snake.common.ILogger;
import snake.common.Logger;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.messaging.MessageBus;

/** 负责创建房间，若房间已存在则转为加入。 */
public class RoomService {
  private final ActorManager actorManager;
  private final DistributedCoordinator coordinator;
  private final MessageBus messageBus;
  private final ILogger logger = Logger.getInstance();

  public RoomService(
      ActorManager actorManager, DistributedCoordinator coordinator, MessageBus messageBus) {
    this.actorManager = actorManager;
    this.coordinator = coordinator;
    this.messageBus = messageBus;
  }

  public void createRoom(EnhancedMessage msg) {
    int roomId = msg.getRoomId();

    if (coordinator.roomExists(roomId)) {
      // 房间已存在，直接发布 JOIN 给对应 Actor
      EnhancedMessage joinMsg =
          EnhancedMessage.newInstance()
              .init("JOIN", msg.getUsername(), roomId, msg.getGatewayId(), msg.getRawMessage());
      try {
        GameActor actor = actorManager.getActor(roomId);
        if (actor != null && actor.isRunning()) {
          actor.postMessage(joinMsg);
          joinMsg = null;
        } else {
          String error = "{\"cmd\":\"ERROR\",\"message\":\"Room not available\"}";
          messageBus.publishToPlayer(msg.getGatewayId(), msg.getUsername(), error);
        }
      } finally {
        if (joinMsg != null) joinMsg.recycle();
      }
      return;
    }

    GameActor actor = actorManager.getOrCreateActor(roomId);
    if (actor == null) {
      String error = "{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}";
      messageBus.publishToPlayer(msg.getGatewayId(), msg.getUsername(), error);
      return;
    }

    EnhancedMessage joinMsg =
        EnhancedMessage.newInstance()
            .init("JOIN", msg.getUsername(), roomId, msg.getGatewayId(), msg.getRawMessage());
    try {
      actor.postMessage(joinMsg);
      joinMsg = null;
    } finally {
      if (joinMsg != null) joinMsg.recycle();
    }
    logger.info("Room " + roomId + " created by " + msg.getUsername());
  }
}
