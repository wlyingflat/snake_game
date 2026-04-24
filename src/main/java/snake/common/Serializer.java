package snake.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.Map;
import snake.util.ILogger;
import snake.util.Logger;

public class Serializer implements ISerializer<GameStateData> {
  private static final ObjectMapper mapper =
      new ObjectMapper()
          .disable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
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

  // snake/common/Serializer.java 添加以下静态方法
  public static GameStateData deserializeGameState(String json) {
    return new Serializer().deserialize(json, GameStateData.class);
  }
}
