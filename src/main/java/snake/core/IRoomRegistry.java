package snake.core;

import java.util.List;
import snake.common.RoomListEntry;

public interface IRoomRegistry {
  Room getRoom(int roomId);

  boolean addRoom(Room room);

  void removeRoom(int roomId);

  List<RoomListEntry> getRoomList();
}
