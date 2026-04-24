package snake.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import snake.base.Direction;
import snake.base.JsonUtils;

public class PlayerInputEvent implements GameEvent {
  public final String eventId;
  public final long timestamp;
  public final String username;
  public final int roomId;
  public final Direction direction;

  public PlayerInputEvent(String username, int roomId, Direction direction) {
    this.eventId = UUID.randomUUID().toString();
    this.timestamp = System.currentTimeMillis();
    this.username = username;
    this.roomId = roomId;
    this.direction = direction;
  }

  @Override
  public String getEventType() {
    return "PlayerInput";
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
    payload.put("direction", direction.name());
    node.set("payload", payload);
    try {
      return JsonUtils.MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      return "{}";
    }
  }
}
