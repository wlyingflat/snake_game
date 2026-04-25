package snake.common;

public interface ISerializer<T> {
  String serialize(T obj);

  T deserialize(String json, Class<T> clazz);
}
