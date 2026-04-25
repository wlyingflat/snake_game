package snake.distributed;

import java.util.*;
import org.redisson.api.RedissonClient;
import snake.base.*;

/** 分布式协调器门面，组合各个领域服务，提供与原有完全相同的 API。 所有方法直接委托给对应的领域服务。 */
public class DistributedCoordinator {
  private final RoomRepository roomRepo;
  private final NodeRepository nodeRepo;
  private final PlayerLocationRepository playerLocRepo;
  private final OnlineStatusService onlineStatusService;
  private final LeaderboardService leaderboardService;
  private final String nodeId;

  public DistributedCoordinator(RedissonClient redisson, String nodeId) {
    this.nodeId = nodeId;
    this.roomRepo = new RoomRepository(redisson);
    this.nodeRepo = new NodeRepository(redisson);
    this.playerLocRepo = new PlayerLocationRepository(redisson, nodeId);
    this.onlineStatusService = new OnlineStatusService(redisson);
    this.leaderboardService = new LeaderboardService(redisson);
  }

  public String getNodeId() {
    return nodeId;
  }

  // ==================== 房间管理 ====================
  public boolean tryCreateRoom(int roomId, int maxPlayers) {
    return roomRepo.tryCreateRoom(roomId, maxPlayers, nodeId);
  }

  public void updateRoomInfo(int roomId, int playerCount, boolean isFull) {
    roomRepo.updateRoomInfo(roomId, playerCount, isFull);
  }

  public void deleteRoom(int roomId) {
    roomRepo.deleteRoom(roomId);
  }

  public boolean roomExists(int roomId) {
    return roomRepo.roomExists(roomId);
  }

  public List<RoomEntry> getAllRooms() {
    List<RoomRepository.RoomEntry> entries = roomRepo.getAllRooms();
    List<RoomEntry> result = new ArrayList<>();
    for (RoomRepository.RoomEntry e : entries) {
      result.add(
          new RoomEntry(e.roomId(), e.status(), e.playerCount(), e.maxPlayers(), e.nodeId()));
    }
    return result;
  }

  // 兼容原有的 RoomEntry record
  public record RoomEntry(
      int roomId, String status, int playerCount, int maxPlayers, String nodeId) {}

  // ==================== Worker 管理 ====================
  public void registerWorker(String workerId) {
    nodeRepo.registerWorker(workerId);
  }

  public void unregisterWorker(String workerId) {
    nodeRepo.unregisterWorker(workerId);
  }

  public Set<String> getActiveWorkers() {
    return nodeRepo.getActiveWorkers();
  }

  // ==================== Gateway 管理 ====================
  public void registerGateway(String gatewayId) {
    nodeRepo.registerGateway(gatewayId);
  }

  public void unregisterGateway(String gatewayId) {
    nodeRepo.unregisterGateway(gatewayId);
  }

  // ==================== 房间到 Worker 的映射 ====================
  public boolean assignRoomToWorker(int roomId, String workerId) {
    roomRepo.assignRoomToWorker(roomId, workerId);
    return true;
  }

  public String getRoomWorker(int roomId) {
    return roomRepo.getRoomWorker(roomId);
  }

  public int getRoomCount(String workerId) {
    return roomRepo.getRoomCount(workerId);
  }

  // ==================== 玩家位置管理 ====================
  public void setPlayerLocation(String username, String gatewayId, int roomId) {
    playerLocRepo.setPlayerLocation(username, gatewayId, roomId);
  }

  public PlayerLocation getPlayerLocation(String username) {
    PlayerLocationRepository.PlayerLocation loc = playerLocRepo.getPlayerLocation(username);
    if (loc == null) return null;
    return new PlayerLocation(loc.gatewayId(), loc.roomId());
  }

  public void removePlayerLocation(String username) {
    playerLocRepo.removePlayerLocation(username);
  }

  public void refreshPlayerLocation(String username) {
    playerLocRepo.refreshPlayerLocation(username);
  }

  public record PlayerLocation(String gatewayId, int roomId) {}

  // ==================== 在线状态管理 ====================
  public void markOnline(String username) {
    onlineStatusService.markOnline(username);
  }

  public void refreshOnline(String username) {
    onlineStatusService.refreshOnline(username);
  }

  public void markOffline(String username) {
    onlineStatusService.markOffline(username);
  }

  public boolean isOnline(String username) {
    return onlineStatusService.isOnline(username);
  }

  // ==================== 排行榜 ====================
  public List<UserRank> getLeaderboard(int limit) {
    List<LeaderboardService.UserRank> ranks = leaderboardService.getLeaderboard(limit);
    List<UserRank> result = new ArrayList<>();
    for (LeaderboardService.UserRank r : ranks) {
      result.add(new UserRank(r.rank, r.username, r.score));
    }
    return result;
  }

  public static class UserRank {
    public final int rank;
    public final String username;
    public final int score;

    public UserRank(int rank, String username, int score) {
      this.rank = rank;
      this.username = username;
      this.score = score;
    }
  }
}
