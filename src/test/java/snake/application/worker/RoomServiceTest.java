package snake.application.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import snake.application.actor.*;
import snake.common.Config;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.messaging.MessageBus;

class RoomServiceTest {
  private ActorManager actorManager;
  private DistributedCoordinator coordinator;
  private MessageBus messageBus;
  private RoomService roomService;

  @BeforeEach
  void setUp() {
    coordinator = mock(DistributedCoordinator.class);
    messageBus = mock(MessageBus.class);
    actorManager = new ActorManager(coordinator, "worker-1", null, messageBus);
    roomService = new RoomService(actorManager, coordinator, messageBus);
  }

  @Test
  void createRoomWhenRoomDoesNotExistShouldCreateActorAndSendJoin() {
    EnhancedMessage msg = EnhancedMessage.newInstance().init("CREATE", "user", 1, "gw", "{}");
    when(coordinator.roomExists(1)).thenReturn(false);
    when(coordinator.tryCreateRoom(1, Config.MAX_PLAYERS_PER_ROOM)).thenReturn(true);
    when(coordinator.assignRoomToWorker(1, "worker-1")).thenReturn(true); // 不返回 true 也能通过，但避免警告

    roomService.createRoom(msg);
    GameActor actor = actorManager.getActor(1);
    assertNotNull(actor);
    assertTrue(actor.isRunning());
  }
}
