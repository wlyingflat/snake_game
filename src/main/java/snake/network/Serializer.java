package snake.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import snake.base.GameStateData;
import snake.base.ILogger;
import snake.base.ISerializer;
import snake.base.JsonUtils;
import snake.base.Logger;
import snake.game.event.GameStateDiff;
import snake.game.event.PlayerDiff;

public class Serializer implements ISerializer<GameStateData> {
  private static final ObjectMapper mapper = JsonUtils.MAPPER;
  private static final ILogger logger = Logger.getInstance();

  @Override
  public String serialize(GameStateData state) {
    try {
      Map<String, Object> wrapper = new LinkedHashMap<>();
      wrapper.put("cmd", "STATE");
      wrapper.put("data", state);
      return mapper.writeValueAsString(wrapper);
    } catch (JsonProcessingException e) {
      logger.error("Serialize game state error: " + e.getMessage());
      return null;
    }
  }

  @Override
  public GameStateData deserialize(String json, Class<GameStateData> clazz) {
    try {
      Map<?, ?> root = mapper.readValue(json, Map.class);
      Object data = root.get("data");
      if (data == null) return null;
      return mapper.convertValue(data, GameStateData.class);
    } catch (JsonProcessingException e) {
      logger.error("Deserialize game state error: " + e.getMessage());
      return null;
    }
  }

  public static GameStateData deserializeGameState(String json) {
    return new Serializer().deserialize(json, GameStateData.class);
  }

  // ---- 新增：序列化差分消息 ----
  public String serializeDiff(GameStateDiff diff) {
    try {
      ObjectNode root = mapper.createObjectNode();
      root.put("cmd", "STATE_DIFF");
      root.put("roomId", diff.roomId);
      root.put("seq", diff.seq);

      ObjectNode changes = root.putObject("changes");

      // food
      if (diff.food != null) {
        ObjectNode foodNode = changes.putObject("food");
        foodNode.put("x", diff.food.x);
        foodNode.put("y", diff.food.y);
      }

      // players diff
      if (!diff.players.isEmpty()) {
        ObjectNode playersDiff = changes.putObject("players");
        for (Map.Entry<String, PlayerDiff> entry : diff.players.entrySet()) {
          PlayerDiff pd = entry.getValue();
          ObjectNode playerNode = playersDiff.putObject(entry.getKey());
          ObjectNode headNode = playerNode.putObject("newHead");
          headNode.put("x", pd.newHead.x);
          headNode.put("y", pd.newHead.y);
          playerNode.put("removeTail", pd.removeTail);
          playerNode.put("length", pd.length);
        }
      }

      // died
      if (!diff.died.isEmpty()) {
        ArrayNode diedArr = changes.putArray("died");
        diff.died.forEach(diedArr::add);
      }

      // newPlayers
      if (!diff.newPlayers.isEmpty()) {
        ArrayNode newPlayersArr = changes.putArray("newPlayers");
        for (GameStateData.PlayerInfo pi : diff.newPlayers) {
          newPlayersArr.add(mapper.valueToTree(pi));
        }
      }

      // removedPlayers
      if (!diff.removedPlayers.isEmpty()) {
        ArrayNode removedArr = changes.putArray("removedPlayers");
        diff.removedPlayers.forEach(removedArr::add);
      }

      return mapper.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      logger.error("Serialize diff error: " + e.getMessage());
      return null;
    }
  }
}
