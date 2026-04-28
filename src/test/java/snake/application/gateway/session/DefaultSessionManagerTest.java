package snake.application.gateway.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.*;

class DefaultSessionManagerTest {
  private DefaultSessionManager manager;
  private ClientSession session;

  @BeforeEach
  void setUp() {
    manager = new DefaultSessionManager();
    ChannelId channelId = mock(ChannelId.class);
    when(channelId.asShortText()).thenReturn("test-channel");
    Channel channel = mock(Channel.class);
    when(channel.isActive()).thenReturn(true);
    when(channel.id()).thenReturn(channelId);
    session = new ClientSession(channel);
  }

  @Test
  void registerAndFindSession() {
    manager.registerSession(session);
    assertEquals(session, manager.getSession(session.getSessionId()));
  }

  @Test
  void bindUsernameShouldAllowLookup() {
    manager.registerSession(session);
    manager.bindUsername(session.getSessionId(), "testUser");
    assertEquals(session, manager.getSessionByUsername("testUser"));
  }

  @Test
  void removeSessionShouldUnbind() {
    manager.registerSession(session);
    manager.bindUsername(session.getSessionId(), "testUser");
    manager.removeSession(session.getSessionId());
    assertNull(manager.getSessionByUsername("testUser"));
  }
}
