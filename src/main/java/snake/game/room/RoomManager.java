package snake.game.room;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import snake.base.Config;
import snake.base.GameStateData;
import snake.base.ILogger;
import snake.base.Logger;
import snake.base.RoomListEntry;
import snake.game.notification.IGameClientNotifier;
import snake.game.state.RoomStatus;

public class RoomManager implements IRoomRegistry, IRoomFactory, IRoomDestroyCallback {
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

  @Override
  public void onRoomDestroyed(int roomId, Room room) {
    logger.info("onRoomDestroyed called for room " + roomId);
    removeRoom(roomId);
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
    if (rooms.containsKey(roomId)) {
      logger.warn("Room " + roomId + " already exists, cannot create");
      return null;
    }

    // 如果外部未提供回调，则默认使用 this（即 RoomManager 自身）
    IRoomDestroyCallback effectiveCallback = (callback != null) ? callback : this;

    Room room = new Room(roomId, effectiveNotifier, effectiveCallback, this::notifyRoomListUpdate);
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
    logger.info("removeRoom called for room " + roomId);
    Room removed = rooms.remove(roomId);
    if (removed != null) {
      logger.info("Room " + roomId + " removed from manager. Remaining rooms: " + rooms.size());
      notifyRoomListUpdate();
    } else {
      logger.warn("Room " + roomId + " not found in manager during removal");
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
    logger.debug(
        "notifyRoomListUpdate called, callback is "
            + (roomListUpdateCallback != null ? "set" : "null"));
    if (roomListUpdateCallback != null) {
      roomListUpdateCallback.run();
    }
  }
}
