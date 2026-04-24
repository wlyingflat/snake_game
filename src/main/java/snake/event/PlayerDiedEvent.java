package snake.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import snake.base.JsonUtils;

public class PlayerDiedEvent implements GameEvent {
  public final String eventId;
  public final long timestamp;
  public final String username;
  public final int roomId;
  public final int finalScore;
  public final int finalLength;
  public final String cause;

  public PlayerDiedEvent(
      String username, int roomId, int finalScore, int finalLength, String cause) {
    this.eventId = UUID.randomUUID().toString();
    this.timestamp = System.currentTimeMillis();
    this.username = username;
    this.roomId = roomId;
    this.finalScore = finalScore;
    this.finalLength = finalLength;
    this.cause = cause;
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
