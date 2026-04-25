package snake.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.Recycler;
import snake.base.JsonUtils;
import snake.game.event.Message;

public class EnhancedMessage implements Message {

  private static final Recycler<EnhancedMessage> RECYCLER =
      new Recycler<EnhancedMessage>() {
        @Override
        protected EnhancedMessage newObject(Handle<EnhancedMessage> handle) {
          return new EnhancedMessage(handle);
        }
      };

  private final Recycler.Handle<EnhancedMessage> handle;

  public static EnhancedMessage newInstance() {
    return RECYCLER.get();
  }

  private EnhancedMessage(Recycler.Handle<EnhancedMessage> handle) {
    this.handle = handle;
  }

  private String command;
  private String username;
  private int roomId;
  private String gatewayId;
  private String rawMessage;

  public EnhancedMessage init(
      String command, String username, int roomId, String gatewayId, String rawMessage) {
    this.command = command;
    this.username = username;
    this.roomId = roomId;
    this.gatewayId = gatewayId;
    this.rawMessage = rawMessage;
    return this;
  }

  @Override
  public String type() {
    return command;
  }

  public String getCommand() {
    return command;
  }

  public String getUsername() {
    return username;
  }

  public int getRoomId() {
    return roomId;
  }

  public String getGatewayId() {
    return gatewayId;
  }

  public String getRawMessage() {
    return rawMessage;
  }

  public void recycle() {
    this.command = null;
    this.username = null;
    this.gatewayId = null;
    this.rawMessage = null;
    handle.recycle(this);
  }

  public String toJson() {
    ObjectNode node = JsonUtils.MAPPER.createObjectNode();
    node.put("command", command);
    node.put("username", username);
    node.put("roomId", roomId);
    node.put("gatewayId", gatewayId);
    node.put("rawMessage", rawMessage);
    try {
      return JsonUtils.MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      return "{}";
    }
  }

  public static EnhancedMessage fromJson(String json) {
    try {
      JsonNode node = JsonUtils.MAPPER.readTree(json);
      EnhancedMessage msg = newInstance();
      msg.init(
          node.get("command").asText(),
          node.get("username").asText(),
          node.get("roomId").asInt(),
          node.get("gatewayId").asText(),
          node.get("rawMessage").asText());
      return msg;
    } catch (Exception e) {
      return null;
    }
  }
}
