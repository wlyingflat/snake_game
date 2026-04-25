package snake.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.Recycler;
import snake.base.JsonUtils;

public class PlayerDiedEvent implements GameEvent {

  // ---------- 对象池 ----------
  private static final Recycler<PlayerDiedEvent> RECYCLER =
      new Recycler<PlayerDiedEvent>() {
        @Override
        protected PlayerDiedEvent newObject(Handle<PlayerDiedEvent> handle) {
          return new PlayerDiedEvent(handle);
        }
      };

  private final Recycler.Handle<PlayerDiedEvent> handle;

  public static PlayerDiedEvent newInstance() {
    return RECYCLER.get();
  }

  private PlayerDiedEvent(Recycler.Handle<PlayerDiedEvent> handle) {
    this.handle = handle;
  }

  // 可复用字段（不再是 final，通过 init 赋值）
  public String eventId;
  public long timestamp;
  public String username;
  public int roomId;
  public int finalScore;
  public int finalLength;
  public String cause;

  public PlayerDiedEvent init(
      String username, int roomId, int finalScore, int finalLength, String cause) {
    this.eventId = java.util.UUID.randomUUID().toString();
    this.timestamp = System.currentTimeMillis();
    this.username = username;
    this.roomId = roomId;
    this.finalScore = finalScore;
    this.finalLength = finalLength;
    this.cause = cause;
    return this;
  }

  private void clear() {
    this.username = null;
    this.cause = null;
  }

  public void recycle() {
    clear();
    handle.recycle(this);
  }

  @Override
  public String getEventType() {
    return "PlayerDied";
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
    payload.put("score", finalScore);
    payload.put("finalLength", finalLength);
    payload.put("cause", cause);
    node.set("payload", payload);
    try {
      return JsonUtils.MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      return "{}";
    }
  }
}
