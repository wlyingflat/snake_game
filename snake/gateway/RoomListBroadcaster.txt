package snake.gateway;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import snake.common.*;
import snake.util.*;

public class RoomListBroadcaster {
  private final ConcurrentHashMap<SocketChannel, ClientSession> sessionsMap;

  public RoomListBroadcaster(ConcurrentHashMap<SocketChannel, ClientSession> sessionsMap) {
    this.sessionsMap = sessionsMap;
  }

  public void sendRoomListToClient(ClientSession session) {
    String list = fetchRoomListFromMain();
    String msg = Protocol.ROOM_LIST_UPDATE + "|" + list;
    Logger.debug(
        "[RoomListBroadcaster] Sending room list to client "
            + session.username
            + ", message length: "
            + msg.length());
    session.enqueueResponse(msg);
  }

  public void broadcastRoomList() {
    String list = fetchRoomListFromMain();
    String msg = Protocol.ROOM_LIST_UPDATE + "|" + list;
    Logger.info(
        "[RoomListBroadcaster] Broadcasting room list to "
            + sessionsMap.size()
            + " clients, data:\n"
            + list);
    for (ClientSession session : sessionsMap.values()) {
      session.enqueueResponse(msg);
    }
  }

  private String fetchRoomListFromMain() {
    Logger.debug(
        "[RoomListBroadcaster] Fetching room list from main server on port "
            + Config.ROOM_LIST_QUERY_PORT);
    try (Socket socket = new Socket("localhost", Config.ROOM_LIST_QUERY_PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
      out.println("LIST");
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null) {
        sb.append(line).append("\n");
      }
      String result = sb.toString();
      Logger.info("[RoomListBroadcaster] Fetched room list from main:\n" + result);
      return result;
    } catch (IOException e) {
      Logger.error(
          "[RoomListBroadcaster] Failed to fetch room list from main server: " + e.getMessage());
      return "No active rooms.\n";
    }
  }
}
