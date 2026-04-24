package snake.base;

public interface IConfigProvider {
  int getInt(String key, int defaultValue);

  String getString(String key, String defaultValue);
}
