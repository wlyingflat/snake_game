// snake/core/RoomManager.java
package snake.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import snake.common.Config;
import snake.common.GameStateData;
import snake.gateway.ClientSession;
import snake.util.Logger;

public class RoomManager {
  private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
  private final ExecutorService roomPool =
      Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
  private final Map<String, ClientSession> usernameToSession;
  private final Runnable roomListUpdateCallback; // 房间列表更新回调

  public RoomManager(Map<String, ClientSession> usernameToSession, Runnable updateCallback) {
    this.usernameToSession = usernameToSession;
    this.roomListUpdateCallback = updateCallback;
  }

  public boolean createRoom(int roomId) {
    if (rooms.containsKey(roomId)) return false;
    Room room = new Room(roomId, this::sendToClient, this::removeRoom, this::notifyRoomListUpdate);
    rooms.put(roomId, room);
    roomPool.submit(room);
    Logger.info("Room " + roomId + " created.");
    notifyRoomListUpdate(); // 新房间创建时立即推送
    return true;
  }

  /** 房间状态变化时调用（玩家加入/离开） */
  private void roomStatusChanged(int roomId) {
    notifyRoomListUpdate();
  }

  /** 通知网关推送房间列表更新 */
  private void notifyRoomListUpdate() {
    if (roomListUpdateCallback != null) {
      roomListUpdateCallback.run();
    }
  }

  // 原子删除房间，避免误删新房间
  private void removeRoom(int roomId, Room roomInstance) {
    boolean removed = rooms.remove(roomId, roomInstance);
    if (removed) {
      Logger.info("Room " + roomId + " removed from manager.");
      notifyRoomListUpdate(); // 房间销毁时推送
    } else {
      Logger.warn("Room " + roomId + " already replaced, skip removal.");
    }
  }

  public void sendToRoom(int roomId, Message msg) {
    Room room = rooms.get(roomId);
    if (room != null) {
      room.post(msg);
    } else {
      Logger.warn("Room " + roomId + " not found");
    }
  }

  public GameStateData getSnapshot(int roomId, String username) {
    Room room = rooms.get(roomId);
    return room != null ? room.getSnapshot(username) : null;
  }

  private void sendToClient(String username, String message) {
    ClientSession session = usernameToSession.get(username);
    if (session != null && session.channel.isOpen()) {
      // 解析房间加入结果，更新会话状态
      if (message.startsWith("JOIN_OK ")) {
        String[] parts = message.split(" ");
        if (parts.length >= 2) {
          int roomId = Integer.parseInt(parts[1]);
          session.roomId = roomId;
          Logger.debug("Updated session " + username + " roomId to " + roomId);
        }
      } else if ("JOIN_FAIL".equals(message)) {
        session.roomId = -1;
        Logger.debug("Reset session " + username + " roomId to -1 (join failed)");
      }
      session.enqueueResponse(message);
    } else if (session == null) {
      Logger.warn("Cannot send message to " + username + ": session not found");
    } else {
      Logger.warn("Cannot send message to " + username + ": channel closed");
    }
  }

  // 返回纯文本房间列表，供 Gateway 直接返回给客户端或查询服务使用
  public String getRoomListDataOnly() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Integer, Room> entry : rooms.entrySet()) {
      int roomId = entry.getKey();
      Room room = entry.getValue();
      GameStateData snapshot = room.getSnapshot(null);
      if (snapshot != null) {
        String status = snapshot.activePlayers >= Config.MAX_PLAYERS_PER_ROOM ? "FULL" : "OPEN";
        sb.append(
            String.format(
                "%-3d %-7s %2d/%-4d %-7d %-10s\n",
                roomId,
                status,
                snapshot.activePlayers,
                Config.MAX_PLAYERS_PER_ROOM,
                Config.BASE_ROOM_PORT + roomId,
                formatTime(System.currentTimeMillis() / 1000)));
      }
    }
    if (sb.length() == 0) {
      sb.append("No active rooms.\n");
    }
    return sb.toString();
  }

  private String formatTime(long timestamp) {
    return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(timestamp * 1000));
  }

  public void shutdown() {
    roomPool.shutdown();
  }
}
