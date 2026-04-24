package snake.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import snake.common.Config;
import snake.common.Direction;
import snake.common.RoomListEntry;
import snake.common.RoomStatus;
import snake.core.InputMsg;
import snake.core.JoinRoomMsg;
import snake.core.LeaveRoomMsg;
import snake.core.Room;
import snake.core.RoomManager;
import snake.util.ILogger;
import snake.util.Logger;

public class GameCommandHandler {
  private final RoomManager roomManager;
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ILogger logger = Logger.getInstance();

  public GameCommandHandler(
      RoomManager roomManager, SessionManager sessionManager, HeartbeatService heartbeatService) {
    this.roomManager = roomManager;
    this.sessionManager = sessionManager;
    this.heartbeatService = heartbeatService;
  }

  public void handleUser(ClientSession session, JsonNode params) {
    String username = params.get("username").asText();
    session.username = username;
    sessionManager.bindUsername(session.getSessionId(), username);
    logger.info("Client identified: " + username);
    session.sendMessage(buildRoomListJson());
    heartbeatService.refresh(session);
  }

  public void handleQuit(ClientSession session, JsonNode params) {
    if (session.roomId != -1) {
      Room room = roomManager.getRoom(session.roomId);
      if (room != null) room.post(new LeaveRoomMsg(session.username));
    }
    session.close();
  }

  public void handleJoin(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int roomId = params.get("roomId").asInt();
    Room room = roomManager.getRoom(roomId);
    if (room != null) {
      room.post(new JoinRoomMsg(session.username));
    } else {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Room not found\"}");
    }
    heartbeatService.refresh(session);
  }

  public void handleCreate(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int newRoomId = params.get("roomId").asInt();
    Room room = roomManager.createRoom(newRoomId, null, (id, r) -> {});
    if (room != null) {
      room.post(new JoinRoomMsg(session.username));
    } else {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}");
    }
    heartbeatService.refresh(session);
  }

  public void handleInput(ClientSession session, JsonNode params) {
    if (session.username == null || session.roomId == -1) return;
    Direction dir = Direction.valueOf(params.get("direction").asText());
    Room room = roomManager.getRoom(session.roomId);
    if (room != null) room.post(new InputMsg(session.username, dir));
    heartbeatService.refresh(session);
  }

  public void handleRoomList(ClientSession session, JsonNode params) {
    session.sendMessage(buildRoomListJson());
    heartbeatService.refresh(session);
  }

  public void handlePing(ClientSession session, JsonNode params) {
    session.sendMessage("{\"cmd\":\"PONG\"}");
    heartbeatService.refresh(session);
  }

  public void handlePong(ClientSession session, JsonNode params) {
    session.pendingPong = false;
    session.lastHeartbeat = System.currentTimeMillis() / 1000;
    heartbeatService.refresh(session);
  }

  private String buildRoomListJson() {
    ArrayNode rooms = mapper.createArrayNode();
    List<RoomListEntry> entries = roomManager.getRoomList();
    for (RoomListEntry entry : entries) {
      ObjectNode room = mapper.createObjectNode();
      room.put("id", entry.roomId);
      room.put("status", entry.status == RoomStatus.OPEN ? "OPEN" : "FULL");
      room.put("players", entry.playerCount);
      room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
      rooms.add(room);
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("cmd", "ROOM_LIST");
    response.set("rooms", rooms);
    try {
      return mapper.writeValueAsString(response);
    } catch (Exception e) {
      return "{\"cmd\":\"ERROR\",\"message\":\"Cannot build room list\"}";
    }
  }
}
