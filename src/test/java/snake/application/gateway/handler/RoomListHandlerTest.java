package snake.application.gateway.handler;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.*;
import snake.application.gateway.session.ClientSession;
import snake.common.JsonUtils;
import snake.distributed.DistributedCoordinator;

class RoomListHandlerTest {
  private DistributedCoordinator coordinator;
  private RoomListHandler handler;
  private ClientSession session;

  @BeforeEach
  void setUp() {
    coordinator = mock(DistributedCoordinator.class);
    handler = new RoomListHandler(coordinator);
    session = mock(ClientSession.class);
  }

  @Test
  void shouldSendRoomList() {
    when(coordinator.getAllRooms())
        .thenReturn(List.of(new DistributedCoordinator.RoomEntry(1, "OPEN", 2, 4, "w1")));
    ObjectNode payload = JsonUtils.MAPPER.createObjectNode();
    handler.handle(session, payload);
    verify(session).sendMessage(contains("ROOM_LIST"));
  }
}
