package snake.core;

import com.lmax.disruptor.EventFactory;

public final class MessageEvent {
  private Message message;

  public Message getMessage() {
    return message;
  }

  public void setMessage(Message message) {
    this.message = message;
  }

  public void clear() {
    this.message = null;
  }

  public static final EventFactory<MessageEvent> FACTORY = MessageEvent::new;
}
