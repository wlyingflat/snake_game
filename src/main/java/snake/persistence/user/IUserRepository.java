package snake.persistence.user;

import java.util.List;
import snake.base.User;

public interface IUserRepository {
  User findByName(String username);

  void save(User user);

  void delete(String username);

  List<User> findAll();

  /** 创建新用户，成功返回 true（如用户名已存在返回 false） */
  boolean createUser(User user);

  /** 更新用户的在线状态和最后活跃时间 */
  void updateOnlineStatus(String username, boolean online, long lastActive);
}
