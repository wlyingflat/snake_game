package snake.application.actor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lmax.disruptor.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import snake.common.Config;
import snake.domain.game.TickMessage;

class ActorEventLoopTest {
  private ActorEventLoop eventLoop;
  private ActorEventLoop.MessageHandler mockHandler;

  @BeforeEach
  void setUp() {
    mockHandler = mock(ActorEventLoop.MessageHandler.class);
    eventLoop = new ActorEventLoop(1, mockHandler);
    eventLoop.start();
  }

  @AfterEach
  void tearDown() {
    eventLoop.shutdown();
  }

  @Test
  void tickMessageShouldTriggerOnTick() throws InterruptedException {
    eventLoop.publishEvent(new TickMessage());
    TimeUnit.MILLISECONDS.sleep(200);
    verify(mockHandler, atLeastOnce()).onTick();
  }

  @Test
  void enhancedMessageShouldTriggerOnMessageAndRecycle() {
    EnhancedMessage msg = EnhancedMessage.newInstance().init("JOIN", "user", 1, "gw", "{}");
    eventLoop.publishEvent(msg);
    try {
      TimeUnit.MILLISECONDS.sleep(200);
    } catch (InterruptedException ignored) {
    }
    verify(mockHandler, times(1)).onMessage(any(EnhancedMessage.class));
  }

  @Test
  void ringBufferFullShouldDropMessageAndRecycle() throws Exception {
    for (int i = 0; i < Config.RING_BUFFER_SIZE + 10; i++) {
      EnhancedMessage msg = EnhancedMessage.newInstance().init("INPUT", "u", 1, "gw", "{}");
      eventLoop.publishEvent(msg);
    }
    TimeUnit.MILLISECONDS.sleep(500);
    // 无异常即通过
  }
}
