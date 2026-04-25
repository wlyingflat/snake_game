package snake.application.actor;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.concurrent.atomic.AtomicBoolean;
import snake.common.Config;
import snake.common.ILogger;
import snake.common.Logger;
import snake.domain.game.Message;
import snake.domain.game.MessageEvent;
import snake.domain.game.TickMessage;

/** 负责 Disruptor 的创建、启动、关闭，以及安全的异步事件发布。 保持线程安全并提供背压处理（满则丢弃并回收 EnhancedMessage）。 */
public class ActorEventLoop {
  private final int roomId;
  private final Disruptor<MessageEvent> disruptor;
  private final RingBuffer<MessageEvent> ringBuffer;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ILogger logger = Logger.getInstance();

  public ActorEventLoop(int roomId, MessageHandler handler) {
    this.roomId = roomId;
    this.disruptor =
        new Disruptor<>(
            MessageEvent.FACTORY,
            Config.RING_BUFFER_SIZE,
            r -> {
              Thread t = new Thread(r, "actor-" + roomId);
              t.setDaemon(true);
              return t;
            },
            ProducerType.MULTI,
            new YieldingWaitStrategy());

    this.disruptor.handleEventsWith(
        (event, sequence, endOfBatch) -> {
          Message msg = event.getMessage();
          if (msg instanceof TickMessage) {
            handler.onTick();
          } else if (msg instanceof EnhancedMessage) {
            EnhancedMessage enhanced = (EnhancedMessage) msg;
            try {
              handler.onMessage(enhanced);
            } finally {
              enhanced.recycle(); // 处理完毕回收
            }
          }
          event.clear();
        });

    this.ringBuffer = disruptor.getRingBuffer();
  }

  public void start() {
    disruptor.start();
  }

  public void publishEvent(Message msg) {
    if (!running.get()) return;
    try {
      long sequence = ringBuffer.tryNext();
      MessageEvent event = ringBuffer.get(sequence);
      event.setMessage(msg);
      ringBuffer.publish(sequence);
    } catch (InsufficientCapacityException e) {
      logger.warn("Actor " + roomId + " ring buffer full, dropping message");
      // 若为池化对象，回收
      if (msg instanceof EnhancedMessage) {
        ((EnhancedMessage) msg).recycle();
      }
    }
  }

  public void shutdown() {
    if (running.compareAndSet(true, false)) {
      disruptor.shutdown();
      logger.info("Disruptor for room " + roomId + " shut down");
    }
  }

  /** 消息处理器接口，由 GameActor 提供 tick 和业务消息处理 */
  public interface MessageHandler {
    void onTick();

    void onMessage(EnhancedMessage msg);
  }
}
