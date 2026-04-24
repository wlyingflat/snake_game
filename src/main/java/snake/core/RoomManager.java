// snake/core/RoomManager.java
package snake.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import snake.common.*;
import snake.util.Logger;

public class RoomManager {
  private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
  private final ExecutorService roomPool =
      Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
  private final MessageDispatcher dispatcher;
  private final Runnable roomListUpdateCallback;

  public RoomManager(MessageDispatcher dispatcher, Runnable updateCallback) {
    this.dispatcher = dispatcher;
    this.roomListUpdateCallback = updateCallback;
  }

  public boolean createRoom(int roomId) {
    if (rooms.containsKey(roomId)) return false;
    Room room = new Room(roomId, this::sendToClient, this::removeRoom, this::notifyRoomListUpdate);
    rooms.put(roomId, room);
    roomPool.submit(room);
    Logger.info("Room " + roomId + " created.");
    notifyRoomListUpdate();
    return true;
  }

  private void notifyRoomListUpdate() {
    if (roomListUpdateCallback != null) {
      roomListUpdateCallback.run();
    }
  }

  private void removeRoom(int roomId, Room roomInstance) {
    boolean removed = rooms.remove(roomId, roomInstance);
    if (removed) {
      Logger.info("Room " + roomId + " removed from manager.");
      notifyRoomListUpdate();
    } else {
      Logger.warn("Room " + roomId + " already replaced, skip removal.");
    }
  }

  public void sendToRoom(int roomId, Message msg) {
    Room room = rooms.get(roomId);
    if (room != null && room.isRunning()) {
      room.post(msg);
    } else {
      Logger.debug("Room " + roomId + " not running or missing, message dropped.");
    }
  }

  public GameStateData getSnapshot(int roomId, String username) {
    Room room = rooms.get(roomId);
    return room != null ? room.getSnapshot(username) : null;
  }

  /** 获取轻量级房间列表（供网关直接发送给客户端） 只包含客户端需要的字段：roomId, playerCount, status */
  public List<RoomListEntry> getRoomListForClient() {
    List<RoomListEntry> entries = new ArrayList<>();
    for (Map.Entry<Integer, Room> entry : rooms.entrySet()) {
      int roomId = entry.getKey();
      Room room = entry.getValue();
      if (!room.isRunning()) continue;
      GameStateData snapshot = room.getSnapshot(null);
      if (snapshot != null) {
        RoomListEntry e = new RoomListEntry();
        e.roomId = roomId;
        e.playerCount = snapshot.activePlayers;
        e.status =
            snapshot.activePlayers >= Config.MAX_PLAYERS_PER_ROOM
                ? RoomStatus.FULL
                : RoomStatus.OPEN;
        entries.add(e);
      }
    }
    return entries;
  }

  /** 获取完整房间信息（供内部管理或主服务器查询使用） 包含端口、创建时间等额外字段 */
  public List<RoomInfo> getRoomListSnapshot() {
    List<RoomInfo> list = new ArrayList<>();
    for (Map.Entry<Integer, Room> entry : rooms.entrySet()) {
      int roomId = entry.getKey();
      Room room = entry.getValue();
      if (!room.isRunning()) continue;
      GameStateData snapshot = room.getSnapshot(null);
      if (snapshot != null) {
        RoomInfo info = new RoomInfo();
        info.roomId = roomId;
        info.port = Config.BASE_ROOM_PORT + roomId;
        info.playerCount = snapshot.activePlayers;
        info.maxPlayers = Config.MAX_PLAYERS_PER_ROOM;
        info.status =
            snapshot.activePlayers >= Config.MAX_PLAYERS_PER_ROOM
                ? RoomStatus.FULL
                : RoomStatus.OPEN;
        info.createdAt = System.currentTimeMillis() / 1000;
        list.add(info);
      }
    }
    return list;
  }

  @Deprecated
  public String getRoomListDataOnly() {
    StringBuilder sb = new StringBuilder();
    for (RoomInfo info : getRoomListSnapshot()) {
      sb.append(
          String.format(
              "%-3d %-7s %2d/%-4d %-7d %-10s\n",
              info.roomId,
              info.status == RoomStatus.OPEN ? "OPEN" : "FULL",
              info.playerCount,
              info.maxPlayers,
              info.port,
              formatTime(info.createdAt)));
    }
    if (sb.length() == 0) {
      sb.append("No active rooms.\n");
    }
    return sb.toString();
  }

  private String formatTime(long timestamp) {
    return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(timestamp * 1000));
  }

  private void sendToClient(String username, String message) {
    if (username == null) {
      Logger.warn("Cannot send message to null username");
      return;
    }
    dispatcher.sendToUser(username, message);
  }

  public void shutdown() {
    roomPool.shutdown();
  }
}
