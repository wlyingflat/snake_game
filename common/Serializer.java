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
          .enable(SerializationFeature.INDENT_OUTPUT) // 便于调试，可关闭
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  /** 将游戏状态序列化为 JSON 字符串，并包装为统一格式： {"type":"STATE","data":{...}} */
  public static String serializeGameState(GameStateData state) {
    try {
      Map<String, Object> wrapper = new LinkedHashMap<>();
      wrapper.put("type", Protocol.STATE);
      wrapper.put("data", state);
      return mapper.writeValueAsString(wrapper);
    } catch (JsonProcessingException e) {
      Logger.error("Serialize game state error: " + e.getMessage());
      return null;
    }
  }

  /**
   * 从 JSON 字符串反序列化 GameStateData
   *
   * @param json 完整消息（含 type 字段）
   * @return GameStateData 对象，若解析失败返回 null
   */
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
