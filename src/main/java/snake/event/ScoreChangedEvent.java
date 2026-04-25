package snake.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.Recycler;
import snake.base.JsonUtils;

public class ScoreChangedEvent implements GameEvent {

  private static final Recycler<ScoreChangedEvent> RECYCLER =
      new Recycler<ScoreChangedEvent>() {
        @Override
        protected ScoreChangedEvent newObject(Handle<ScoreChangedEvent> handle) {
          return new ScoreChangedEvent(handle);
        }
      };

  private final Recycler.Handle<ScoreChangedEvent> handle;

  public static ScoreChangedEvent newInstance() {
    return RECYCLER.get();
  }

  private ScoreChangedEvent(Recycler.Handle<ScoreChangedEvent> handle) {
    this.handle = handle;
  }

  public String eventId;
  public long timestamp;
  public String username;
  public int roomId;
  public int newScore;
  public int delta;

  public ScoreChangedEvent init(String username, int roomId, int newScore, int delta) {
    this.eventId = java.util.UUID.randomUUID().toString();
    this.timestamp = System.currentTimeMillis();
    this.username = username;
    this.roomId = roomId;
    this.newScore = newScore;
    this.delta = delta;
    return this;
  }

  private void clear() {
    this.username = null;
  }

  public void recycle() {
    clear();
    handle.recycle(this);
  }

  @Override
  public String getEventType() {
    return "ScoreChanged";
  }

  @Override
  public String toJson() {
    ObjectNode node = JsonUtils.MAPPER.createObjectNode();
    node.put("eventId", eventId);
    node.put("eventType", getEventType());
    node.put("timestamp", timestamp);
    node.put("version", 1);
    ObjectNode payload = JsonUtils.MAPPER.createObjectNode();
    payload.put("username", username);
    payload.put("roomId", roomId);
    payload.put("newScore", newScore);
    payload.put("delta", delta);
    node.set("payload", payload);
    try {
      return JsonUtils.MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      return "{}";
    }
  }
}
