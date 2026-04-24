package snake.game.room;

import java.util.List;
import snake.base.RoomListEntry;

public interface IRoomRegistry {
  Room getRoom(int roomId);

  boolean addRoom(Room room);

  void removeRoom(int roomId);

  List<RoomListEntry> getRoomList();
}
