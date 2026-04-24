package snake.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import snake.base.JsonUtils;
import snake.game.event.Message;

/** Worker → Actor 的标准消息格式 包含了消息路由所需的元数据 */
public class EnhancedMessage implements Message {
  private final String command; // 原始命令: JOIN, INPUT, LEAVE
  private final String username; // 玩家名
  private final int roomId; // 房间ID
  private final String gatewayId; // 玩家所在的 Gateway ID
  private final String rawMessage; // 原始 JSON 消息

  public EnhancedMessage(
      String command, String username, int roomId, String gatewayId, String rawMessage) {
    this.command = command;
    this.username = username;
    this.roomId = roomId;
    this.gatewayId = gatewayId;
    this.rawMessage = rawMessage;
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

  /** 序列化为 JSON */
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

  /** 从 JSON 反序列化 */
  public static EnhancedMessage fromJson(String json) {
    try {
      JsonNode node = JsonUtils.MAPPER.readTree(json);
      return new EnhancedMessage(
          node.get("command").asText(),
          node.get("username").asText(),
          node.get("roomId").asInt(),
          node.get("gatewayId").asText(),
          node.get("rawMessage").asText());
    } catch (Exception e) {
      return null;
    }
  }
}
