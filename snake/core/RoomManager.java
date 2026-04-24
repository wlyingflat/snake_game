package snake.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import snake.common.*;
import snake.gateway.ClientSession;
import snake.util.Logger;

public class RoomManager {
  private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
  private final ExecutorService roomPool =
      Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
  private final Map<String, ClientSession> usernameToSession;
  private final Runnable roomListUpdateCallback;

  public RoomManager(Map<String, ClientSession> usernameToSession, Runnable updateCallback) {
    this.usernameToSession = usernameToSession;
    this.roomListUpdateCallback = updateCallback;
  }

  public boolean createRoom(int roomId) {
    Logger.info("[RoomManager] createRoom called for roomId=" + roomId);
    if (rooms.containsKey(roomId)) {
      Logger.warn("[RoomManager] Room " + roomId + " already exists");
      return false;
    }
    Room room = new Room(roomId, this::sendToClient, this::removeRoom, this::notifyRoomListUpdate);
    rooms.put(roomId, room);
    Logger.info("[RoomManager] Room " + roomId + " added to map, total rooms now: " + rooms.size());
    roomPool.submit(room);
    Logger.info("[RoomManager] Room thread submitted");
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
    if (username == null) {
      Logger.warn("Cannot send message to null username");
      return;
    }
    ClientSession session = usernameToSession.get(username);
    if (session != null && session.channel.isOpen()) {
      try {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(message);
        String cmd = root.get("cmd").asText();
        if ("JOIN_OK".equals(cmd)) {
          int roomId = root.get("roomId").asInt();
          session.roomId = roomId;
          Logger.debug("Updated session " + username + " roomId to " + roomId);
        } else if ("YOU_DIED".equals(cmd)) {
          session.roomId = -1;
          Logger.debug("Reset session " + username + " roomId to -1 (player died)");
        } else if ("JOIN_FAIL".equals(cmd)) {
          session.roomId = -1;
        }
      } catch (Exception e) {
        // 不是 JSON 或解析失败，忽略
      }
      session.enqueueResponse(message);
    } else if (session == null) {
      Logger.warn("Cannot send message to " + username + ": session not found");
    } else {
      Logger.warn("Cannot send message to " + username + ": channel closed");
    }
  }

  public String getRoomListDataOnly() {
    Logger.debug("[RoomManager] getRoomListDataOnly: rooms size = " + rooms.size());
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Integer, Room> entry : rooms.entrySet()) {
      int roomId = entry.getKey();
      Room room = entry.getValue();
      GameStateData snapshot = room.getSnapshot(null);
      if (snapshot == null) {
        Logger.warn("[RoomManager] Room " + roomId + " snapshot is null, skipping");
        continue;
      }
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
    if (sb.length() == 0) {
      sb.append("No active rooms.\n");
      Logger.info("[RoomManager] No rooms found, returning empty list");
    } else {
      Logger.debug("[RoomManager] Built room list:\n" + sb.toString());
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
