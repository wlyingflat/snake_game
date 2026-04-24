package snake.server;

import java.util.List;
import snake.common.User;

public interface IUserRepository {
  User findByName(String username);

  void save(User user);

  void delete(String username);

  List<User> findAll(); // 用于快照
}
