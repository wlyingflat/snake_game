package snake.game.notification;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import snake.base.ILeaderboardRepository;
import snake.base.ILogger;
import snake.base.Logger;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;

public class SessionBasedNotifier implements IGameClientNotifier {
  private final SessionManager sessionManager;
  private final ILeaderboardRepository leaderboardRepo;
  private final ILogger logger = Logger.getInstance();

  public SessionBasedNotifier(
      SessionManager sessionManager, ILeaderboardRepository leaderboardRepo) {
    this.sessionManager = sessionManager;
    this.leaderboardRepo = leaderboardRepo;
  }

  @Override
  public void notifyPlayer(String username, String message) {
    ClientSession session = sessionManager.getSessionByUsername(username);
    if (session != null && !session.closed) {
      // 注意：session.roomId 不再通过解析 "JOIN_OK" 消息更新，完全依赖 onJoinResult 回调
      // 但消息本身仍需要发送给客户端
      session.sendMessage(message);
    }
  }

  @Override
  public void updateHighScore(String username, int score) {
    CompletableFuture.runAsync(
            () -> {
              leaderboardRepo.updateHighScore(username, score);
            })
        .exceptionally(
            ex -> {
              logger.error(
                  "Async high score update failed for " + username + ": " + ex.getMessage());
              return null;
            });
  }

  @Override
  public void notifyPlayers(Collection<String> usernames, String message) {
    for (String u : usernames) notifyPlayer(u, message);
  }

  @Override
  public void onLeave(String username) {
    ClientSession session = sessionManager.getSessionByUsername(username);
    if (session != null && !session.closed) {
      int oldRoomId = session.roomId;
      session.roomId = -1;
      logger.info("User " + username + " left room, roomId changed from " + oldRoomId + " to -1");
    } else {
      logger.warn("onLeave called for " + username + " but session not found or closed");
    }
  }

  @Override
  public void onJoinResult(String username, int roomId, boolean success) {
    ClientSession session = sessionManager.getSessionByUsername(username);
    if (session == null || session.closed) return;

    if (success) {
      session.roomId = roomId;
      logger.debug("User " + username + " joined room " + roomId + " (callback)");
    } else {
      session.roomId = -1;
      logger.debug("User " + username + " failed to join room (callback)");
    }
  }
}
