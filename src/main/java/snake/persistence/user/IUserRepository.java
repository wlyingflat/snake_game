package snake.persistence.user;

import java.util.List;
import snake.base.User;

public interface IUserRepository {
  User findByName(String username);

  void save(User user);

  void delete(String username);

  List<User> findAll(); // 用于快照
}
