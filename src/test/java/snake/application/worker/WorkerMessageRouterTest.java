package snake.application.worker;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import snake.application.actor.*;
import snake.infrastructure.messaging.MessageBus;

class WorkerMessageRouterTest {
  private ActorManager actorManager;
  private MessageBus messageBus;
  private RoomService roomService;
  private WorkerMessageRouter router;

  @BeforeEach
  void setUp() {
    actorManager = mock(ActorManager.class);
    messageBus = mock(MessageBus.class);
    roomService = mock(RoomService.class);
    router = new WorkerMessageRouter(actorManager, messageBus, roomService);
  }

  @Test
  void createCommandShouldDelegateToRoomService() {
    EnhancedMessage msg = EnhancedMessage.newInstance().init("CREATE", "u", 1, "gw", "{}");
    byte[] proto = msg.toProtobuf();
    router.route(proto);
    verify(roomService).createRoom(any(EnhancedMessage.class));
  }

  @Test
  void joinCommandWhenActorExistsShouldPostMessage() {
    GameActor actor = mock(GameActor.class);
    when(actor.isRunning()).thenReturn(true);
    when(actorManager.getActor(2)).thenReturn(actor);

    EnhancedMessage msg = EnhancedMessage.newInstance().init("JOIN", "u", 2, "gw", "{}");
    byte[] proto = msg.toProtobuf();
    router.route(proto);
    verify(actor).postMessage(any(EnhancedMessage.class));
  }

  @Test
  void commandWhenActorNotFoundShouldSendError() {
    when(actorManager.getActor(3)).thenReturn(null);
    EnhancedMessage msg = EnhancedMessage.newInstance().init("INPUT", "u", 3, "gw", "{}");
    byte[] proto = msg.toProtobuf();
    router.route(proto);
    verify(messageBus).publishToPlayer(eq("gw"), eq("u"), anyString());
  }
}
