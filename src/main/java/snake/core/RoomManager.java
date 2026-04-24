package snake.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.*;
import snake.util.ILogger;
import snake.util.Logger;

public class RoomManager implements IRoomRegistry, IRoomFactory {
  private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
  private IGameClientNotifier notifier;
  private Runnable roomListUpdateCallback;
  private final ILogger logger = Logger.getInstance();

  public RoomManager(IGameClientNotifier notifier, Runnable updateCallback) {
    this.notifier = notifier;
    this.roomListUpdateCallback = updateCallback;
  }

  public void setNotifier(IGameClientNotifier notifier) {
    this.notifier = notifier;
  }

  public void setRoomListUpdateCallback(Runnable callback) {
    this.roomListUpdateCallback = callback;
  }

  @Override
  public Room createRoom(int roomId, IGameClientNotifier notifier, IRoomDestroyCallback callback) {
    IGameClientNotifier effectiveNotifier = notifier != null ? notifier : this.notifier;
    if (effectiveNotifier == null) {
      logger.error("Cannot create room: notifier is null");
      return null;
    }
    if (rooms.containsKey(roomId)) return null;
    Room room = new Room(roomId, effectiveNotifier, callback, this::notifyRoomListUpdate);
    rooms.put(roomId, room);
    logger.info("Room " + roomId + " created.");
    notifyRoomListUpdate();
    return room;
  }

  @Override
  public Room getRoom(int roomId) {
    return rooms.get(roomId);
  }

  @Override
  public boolean addRoom(Room room) {
    if (rooms.containsKey(room.getRoomId())) return false;
    rooms.put(room.getRoomId(), room);
    notifyRoomListUpdate();
    return true;
  }

  @Override
  public void removeRoom(int roomId) {
    Room removed = rooms.remove(roomId);
    if (removed != null) {
      logger.info("Room " + roomId + " removed from manager.");
      notifyRoomListUpdate();
    }
  }

  @Override
  public List<RoomListEntry> getRoomList() {
    List<RoomListEntry> entries = new ArrayList<>();
    for (Map.Entry<Integer, Room> entry : rooms.entrySet()) {
      int roomId = entry.getKey();
      Room room = entry.getValue();
      if (!room.isRunning()) continue;
      GameStateData snapshot = room.getSnapshot(null);
      if (snapshot != null) {
        entries.add(
            new RoomListEntry(
                roomId,
                snapshot.activePlayers,
                snapshot.activePlayers >= Config.MAX_PLAYERS_PER_ROOM
                    ? RoomStatus.FULL
                    : RoomStatus.OPEN));
      }
    }
    return entries;
  }

  private void notifyRoomListUpdate() {
    if (roomListUpdateCallback != null) roomListUpdateCallback.run();
  }
}
