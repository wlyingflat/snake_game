// snake/common/RoomListEntry.java
package snake.common;

public class RoomListEntry {
  public int roomId;
  public int playerCount;
  public RoomStatus status;

  public RoomListEntry() {}

  public RoomListEntry(int roomId, int playerCount, RoomStatus status) {
    this.roomId = roomId;
    this.playerCount = playerCount;
    this.status = status;
  }
}
