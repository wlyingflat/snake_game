package snake.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import snake.base.JsonUtils;

public class ScoreChangedEvent implements GameEvent {
  public final String eventId;
  public final long timestamp;
  public final String username;
  public final int roomId;
  public final int newScore;
  public final int delta;

  public ScoreChangedEvent(String username, int roomId, int newScore, int delta) {
    this.eventId = UUID.randomUUID().toString();
    this.timestamp = System.currentTimeMillis();
    this.username = username;
    this.roomId = roomId;
    this.newScore = newScore;
    this.delta = delta;
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
