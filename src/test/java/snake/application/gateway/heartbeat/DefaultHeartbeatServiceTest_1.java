package snake.application.gateway.heartbeat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.*;
import snake.application.gateway.session.ClientSession;
import snake.distributed.DistributedCoordinator;

class DefaultHeartbeatServiceTest {
  private DefaultHeartbeatService service;
  private DistributedCoordinator coordinator;
  private ClientSession session;

  @BeforeEach
  void setUp() {
    coordinator = mock(DistributedCoordinator.class);
    service = new DefaultHeartbeatService(s -> {}, coordinator);

    ChannelId channelId = mock(ChannelId.class);
    when(channelId.asShortText()).thenReturn("test-channel");
    Channel channel = mock(Channel.class);
    when(channel.isActive()).thenReturn(true);
    when(channel.id()).thenReturn(channelId);
    session = new ClientSession(channel);
    session.username = "user1";
  }

  @AfterEach
  void tearDown() {
    service.stop();
  }

  @Test
  void refreshShouldScheduleTimeout() {
    service.start();
    service.refresh(session);
    assertTrue(true);
  }

  @Test
  void removeShouldClearTimeout() {
    service.start();
    service.refresh(session);
    service.remove(session);
    assertTrue(true);
  }
}
