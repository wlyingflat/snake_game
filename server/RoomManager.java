package snake.server;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.*;
import snake.util.*;

public class RoomManager {
  private ConcurrentHashMap<Integer, RoomInfo> rooms = new ConcurrentHashMap<>();
  private int basePort;

  public RoomManager() {
    this.basePort = Config.BASE_ROOM_PORT;
    for (int i = 0; i < Config.MAX_ROOMS; i++) {
      RoomInfo room = new RoomInfo();
      room.roomId = i;
      room.port = basePort + i;
      room.maxPlayers = Config.MAX_PLAYERS_PER_ROOM;
      room.status = RoomStatus.CLOSED;
      room.playerCount = 0;
      rooms.put(i, room);
    }
  }

  public synchronized boolean createRoom(int roomId, String creator) {
    RoomInfo room = rooms.get(roomId);
    if (room == null || room.status != RoomStatus.CLOSED) return false;
    try {
      String javaHome = System.getProperty("java.home");
      String classpath = System.getProperty("java.class.path");
      ProcessBuilder pb =
          new ProcessBuilder(
              javaHome + "/bin/java",
              "-cp",
              classpath,
              "snake.room.RoomServer",
              String.valueOf(roomId),
              String.valueOf(room.port));
      pb.inheritIO();
      Process process = pb.start();
      room.process = process;
      // 状态保持 CLOSED，等待房间进程注册后变为 OPEN
      return true;
    } catch (IOException e) {
      Logger.error("Failed to start room process: " + e.getMessage());
      return false;
    }
  }

  public synchronized boolean joinRoom(int roomId, String username) {
    RoomInfo room = rooms.get(roomId);
    // 只做准入检查，不修改计数，计数由房间进程上报
    if (room == null || room.status != RoomStatus.OPEN) return false;
    if (room.playerCount >= room.maxPlayers) return false;
    return true;
  }

  /** 由房间进程通过 UPDATE 命令调用，更新房间状态 */
  public synchronized void updateRoomStatus(int roomId, int playerCount, int activePlayers) {
    RoomInfo room = rooms.get(roomId);
    if (room != null) {
      room.playerCount = playerCount;
      room.status = (playerCount >= room.maxPlayers) ? RoomStatus.FULL : RoomStatus.OPEN;
      room.lastActivity = System.currentTimeMillis() / 1000;
      // activePlayers 可扩展存储，目前未使用
    }
  }

  public synchronized void registerRoomProcess(int roomId, int port) {
    RoomInfo room = rooms.get(roomId);
    if (room != null) {
      room.status = RoomStatus.OPEN;
      room.playerCount = 0;
      room.port = port;
      room.createdAt = System.currentTimeMillis() / 1000;
      room.lastActivity = System.currentTimeMillis() / 1000;
    }
  }

  public synchronized void unregisterRoom(int roomId) {
    RoomInfo room = rooms.get(roomId);
    if (room != null) {
      room.status = RoomStatus.CLOSED;
      room.playerCount = 0;
    }
  }

  public RoomInfo getRoom(int roomId) {
    return rooms.get(roomId);
  }

  public String getRoomList() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Room List ===\n");
    sb.append("ID  Status  Players  Port    Created\n");
    sb.append("--- ------- ------- ------- ----------\n");
    for (RoomInfo room : rooms.values()) {
      if (room.status == RoomStatus.CLOSED) continue;
      String statusStr = room.status == RoomStatus.OPEN ? "OPEN" : "FULL";
      sb.append(
          String.format(
              "%-3d %-7s %2d/%-4d %-7d %-10s\n",
              room.roomId,
              statusStr,
              room.playerCount,
              room.maxPlayers,
              room.port,
              formatTime(room.createdAt)));
    }
    return sb.toString();
  }

  public String getRoomListDataOnly() {
    StringBuilder sb = new StringBuilder();
    for (RoomInfo room : rooms.values()) {
      if (room.status == RoomStatus.CLOSED) continue;
      String statusStr = room.status == RoomStatus.OPEN ? "OPEN" : "FULL";
      sb.append(
          String.format(
              "%-3d %-7s %2d/%-4d %-7d %-10s\n",
              room.roomId,
              statusStr,
              room.playerCount,
              room.maxPlayers,
              room.port,
              formatTime(room.createdAt)));
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
    for (RoomInfo room : rooms.values()) {
      if (room.process != null && room.process.isAlive()) {
        room.process.destroy();
      }
    }
  }
}
