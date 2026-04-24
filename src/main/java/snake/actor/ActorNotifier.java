package snake.actor;

import java.util.concurrent.CompletableFuture;
import snake.base.Config;
import snake.base.ILeaderboardRepository;
import snake.base.ILogger;
import snake.base.Logger;
import snake.distributed.DistributedCoordinator;

/** Actor 的通知器 负责将消息发送到正确的 Gateway，再由 Gateway 转发给客户端 */
public class ActorNotifier {
  private final DistributedCoordinator coordinator;
  private final ILeaderboardRepository leaderboardRepo;
  private final ILogger logger = Logger.getInstance();

  public ActorNotifier(DistributedCoordinator coordinator, ILeaderboardRepository leaderboardRepo) {
    this.coordinator = coordinator;
    this.leaderboardRepo = leaderboardRepo;
  }

  /**
   * 发送消息给指定玩家
   *
   * @param username 玩家名
   * @param gatewayId 玩家所在 Gateway ID（null 时从 Redis 查询）
   * @param message 消息内容
   */
  public void sendToPlayer(String username, String gatewayId, String message) {
    if (username == null) return;

    // 如果没有指定 gatewayId，从 Redis 查询
    if (gatewayId == null) {
      DistributedCoordinator.PlayerLocation loc = coordinator.getPlayerLocation(username);
      if (loc == null) {
        logger.debug("Player location not found for " + username);
        return;
      }
      gatewayId = loc.gatewayId();
    }

    // 发布到玩家所在 Gateway 的频道
    coordinator.publishToGateway(gatewayId, username, message);

    if (Config.DEBUG_MESSAGE_LOGGING) {
      logger.debug("Actor sent to " + username + " via gateway " + gatewayId);
    }
  }

  /** 更新最高分 */
  public void updateHighScore(String username, int score) {
    CompletableFuture.runAsync(
            () -> {
              leaderboardRepo.updateHighScore(username, score);
            })
        .exceptionally(
            ex -> {
              logger.error("High score update failed: " + ex.getMessage());
              return null;
            });
  }
}
