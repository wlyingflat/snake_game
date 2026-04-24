package snake.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class JsonUtils {
  public static final ObjectMapper MAPPER;

  static {
    MAPPER =
        new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
  }

  private JsonUtils() {
    // 禁止实例化
  }
}
