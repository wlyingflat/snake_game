package snake.infrastructure.auth;

import java.util.Arrays;
import snake.common.ILogger;
import snake.common.Logger;
import snake.common.User;
import snake.distributed.DistributedCoordinator;
import snake.infrastructure.persistence.user.IUserRepository;

public class AuthenticationService implements IAuthenticationService {
  private final IUserRepository userRepo;
  private final DistributedCoordinator coordinator;
  private final boolean distributedMode;
  private final ILogger logger = Logger.getInstance();

  public AuthenticationService(IUserRepository userRepo, DistributedCoordinator coordinator) {
    this.userRepo = userRepo;
    this.coordinator = coordinator;
    this.distributedMode = (coordinator != null);
  }

  @Override
  public boolean register(String username, String password) {
    User user = new User();
    user.name = username;
    user.salt = (int) (Math.random() * 0xFFFFFFFFL);
    user.passwordHash = PasswordUtils.hashPassword(password, user.salt);
    return userRepo.createUser(user);
  }

  @Override
  public boolean login(String username, String password) {
    User user = userRepo.findByName(username);
    if (user == null) {
      return false;
    }

    byte[] hash = PasswordUtils.hashPassword(password, user.salt);
    if (!Arrays.equals(hash, user.passwordHash)) {
      return false;
    }

    // 检查在线状态（只读，不写入 Redis）
    if (distributedMode) {
      if (coordinator.isOnline(username)) {
        logger.warn("User " + username + " already online (Redis check)");
        return false;
      }
    } else {
      if (user.online) {
        logger.warn("User " + username + " already online (DB check)");
        return false;
      }
    }

    long now = System.currentTimeMillis() / 1000;
    // 只更新数据库的 online 字段，不碰 Redis
    userRepo.updateOnlineStatus(username, true, now);
    logger.info("User logged in: " + username);
    return true;
  }

  @Override
  public void logout(String username) {
    long now = System.currentTimeMillis() / 1000;
    userRepo.updateOnlineStatus(username, false, now);
    logger.info("User logged out: " + username);
  }
}
