package snake.common;

public interface IConfigProvider {
  int getInt(String key, int defaultValue);

  String getString(String key, String defaultValue);

  boolean getBoolean(String key, boolean defaultValue); // 新增
}
