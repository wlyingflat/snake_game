package snake.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.Map;
import snake.util.Logger;

public class Serializer {
  private static final ObjectMapper mapper =
      new ObjectMapper()
          .disable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  public static String serializeGameState(GameStateData state) {
    try {
      Map<String, Object> wrapper = new LinkedHashMap<>();
      wrapper.put("cmd", "STATE");
      wrapper.put("data", state);
      return mapper.writeValueAsString(wrapper);
    } catch (JsonProcessingException e) {
      Logger.error("Serialize game state error: " + e.getMessage());
      return null;
    }
  }

  public static GameStateData deserializeGameState(String json) {
    try {
      Map<?, ?> root = mapper.readValue(json, Map.class);
      Object data = root.get("data");
      if (data == null) return null;
      return mapper.convertValue(data, GameStateData.class);
    } catch (JsonProcessingException e) {
      Logger.error("Deserialize game state error: " + e.getMessage());
      return null;
    }
  }
}
