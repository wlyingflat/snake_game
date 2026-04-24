package snake.common;

public class RoomInfo {
  public int roomId;
  public int port;
  public int playerCount;
  public int maxPlayers;
  public RoomStatus status;
  public long createdAt; // timestamp seconds
  public long lastActivity; // timestamp seconds
  public Process process; // Java 进程对象，用于监控（仅主服务器使用）
}
